package routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;
import routing.community.Duration;
import core.Tuple;

/**
 * QLearningRouter — Abstract base class untuk ORQLCI routing.
 *
 * Bertanggung jawab atas:
 * - Struktur Q-Table: qvalues[dest][relay] sesuai notasi Qd(s,x) di paper
 * - Operasi Q-Table: updateQDirect (Eq.10), updateQRelay (Eq.9),
 * ageQTable (Eq.11), getNeighborMaxQPrime (Eq.8)
 * - Pencatatan connection history (startTimestamps, connHistory)
 * - Topic assignment untuk InterestReport
 *
 * Referensi: Liu et al., "Opportunistic Routing using Q-Learning
 * with Context Information", Section 3.2
 */
public abstract class QLearningRouter extends ActiveRouter {

	public static final String MESSAGE_TOPICS_S = "topic";

	// -------------------------------------------------------------------------
	// Q-TABLE
	// Dimensi: qvalues[d][x] ≡ Qd(s, x) di paper
	// d = alamat destination node (0 .. totalDest-1)
	// x = alamat relay/action node (0 .. totalAction-1)
	// State 's' (buffer level) di-encode di CCRouting, tidak masuk indeks tabel.
	// -------------------------------------------------------------------------
	protected double[][] qvalues;
	protected int totalDest; // diisi oleh CCRouting dari config totalState
	protected int totalAction; // diisi oleh CCRouting dari config totalAction

	// -------------------------------------------------------------------------
	// LEARNING PARAMETERS — nilai default sesuai paper Section 4.1
	// Dapat di-override oleh CCRouting sesuai config.
	// -------------------------------------------------------------------------
	protected double learningRate = 0.8; // α
	protected double discountFactor = 0.6; // γ (nilai statis; γd dihitung di CCRouting)
	protected double agingOmega = 0.98; // ω untuk Eq.11

	// Timestamp terakhir Q-table di-age, untuk menghitung t pada Eq.11
	protected double lastQAgeTime = 0.0;

	// -------------------------------------------------------------------------
	// CONNECTION HISTORY
	// -------------------------------------------------------------------------
	protected Map<DTNHost, Double> startTimestamps;
	protected Map<DTNHost, List<Duration>> connHistory;

	// =========================================================================
	// CONSTRUCTOR
	// =========================================================================

	public QLearningRouter(Settings s) {
		super(s);
		this.startTimestamps = new HashMap<>();
		this.connHistory = new HashMap<>();
		// totalDest & totalAction di-set oleh CCRouting sebelum initQTable()
		this.totalDest = 5;
		this.totalAction = 5;
		initQTable();
	}

	protected QLearningRouter(QLearningRouter r) {
		super(r);
		// startTimestamps & connHistory TIDAK di-share antar node
		this.startTimestamps = new HashMap<>();
		this.connHistory = new HashMap<>();
		this.totalDest = r.totalDest;
		this.totalAction = r.totalAction;
		this.learningRate = r.learningRate;
		this.discountFactor = r.discountFactor;
		this.agingOmega = r.agingOmega;
		this.lastQAgeTime = 0.0;
		initQTable(); // Q-table baru, tidak di-share
	}

	// =========================================================================
	// Q-TABLE OPERATIONS
	// =========================================================================

	/**
	 * Inisialisasi Q-Table dengan semua nilai = 0.
	 * Dipanggil setelah totalDest & totalAction di-set oleh CCRouting.
	 */
	protected void initQTable() {
		qvalues = new double[totalDest][];
		for (int i = 0; i < totalDest; i++) {
			qvalues[i] = new double[totalAction];
		}
	}

	/**
	 * Membaca Qd(s,x) = qvalues[destAddr][actionAddr].
	 * Return 0.0 jika indeks di luar batas.
	 */
	public double getQV(int destAddr, int actionAddr) {
		if (destAddr < 0 || destAddr >= totalDest)
			return 0.0;
		if (actionAddr < 0 || actionAddr >= totalAction)
			return 0.0;
		return qvalues[destAddr][actionAddr];
	}

	/**
	 * Eq.10 — Update Q saat encountered node x ADALAH destination d.
	 *
	 * Qd(s,x) ← (1-α) × Qd(s,x) + α × Rd(s,x)
	 * Rd(s,x) = 1 (karena x == d, Eq.6)
	 *
	 * @param destAddr  alamat d (destination)
	 * @param relayAddr alamat x (== destAddr)
	 */
	public void updateQDirect(int destAddr, int relayAddr) {
		if (destAddr < 0 || destAddr >= totalDest)
			return;
		if (relayAddr < 0 || relayAddr >= totalAction)
			return;

		double oldQ = qvalues[destAddr][relayAddr];
		// Eq.10: reward = 1, tidak ada discount term
		qvalues[destAddr][relayAddr] = (1.0 - learningRate) * oldQ + learningRate * 1.0;
	}

	/**
	 * Eq.9 — Update Q saat encountered node x BUKAN destination d.
	 *
	 * Qd(s,x) ← (1-α) × Qd(s,x) + α × γd(s,x) × max_y(Qd(x,y)×P(x,y))
	 *
	 * Catatan: γd(s,x) = γ × BFx (Eq.7) SUDAH dihitung oleh pemanggil
	 * (CCRouting) dan dimasukkan sebagai parameter dynamicDiscount,
	 * sehingga TIDAK ada perkalian BFx lagi di sini.
	 *
	 * @param destAddr        alamat d
	 * @param relayAddr       alamat x (relay, bukan destination)
	 * @param dynamicDiscount γd(s,x) = γ × BFx, hasil Eq.7
	 * @param neighborMaxQP   max_y(Qd(x,y)×P(x,y)), hasil Eq.8
	 */
	public void updateQRelay(int destAddr, int relayAddr,
			double dynamicDiscount, double neighborMaxQP) {
		if (destAddr < 0 || destAddr >= totalDest)
			return;
		if (relayAddr < 0 || relayAddr >= totalAction)
			return;

		double oldQ = qvalues[destAddr][relayAddr];
		// Eq.9: reward = 0, sehingga suku reward gugur
		qvalues[destAddr][relayAddr] = (1.0 - learningRate) * oldQ
				+ learningRate * dynamicDiscount * neighborMaxQP;
	}

	/**
	 * Eq.11 — Aging seluruh Q-Table berdasarkan waktu nyata yang berlalu.
	 *
	 * Qd(s,x) = Qd(s,x)_old × ω^t
	 * t = (now - lastQAgeTime) / SEC_IN_TU (jumlah time unit)
	 *
	 * Dipanggil secara periodik dari CCRouting.update().
	 *
	 * @param secInTimeUnit satuan waktu (detik per time unit), sesuai SEC_IN_TU
	 */
	public void ageQTable(int secInTimeUnit) {
		double now = SimClock.getTime();
		double timeDiff = (now - lastQAgeTime) / secInTimeUnit;
		if (timeDiff <= 0)
			return;

		double mult = Math.pow(agingOmega, timeDiff);
		for (int d = 0; d < totalDest; d++) {
			for (int x = 0; x < totalAction; x++) {
				qvalues[d][x] *= mult;
			}
		}
		lastQAgeTime = now;
	}

	/**
	 * Eq.8 — Menghitung max_y∈Nx [ Qd(x,y) × P(x,y) ].
	 *
	 * Dipanggil oleh node x (router tetangga) untuk menyediakan data
	 * yang dibutuhkan node s dalam Eq.9.
	 *
	 * @param destAddr       alamat destination d
	 * @param encounterProbs Map<nodeAddress, P(x,y)> milik node x
	 * @return nilai maksimum Qd(x,y) × P(x,y) di antara semua y
	 */
	public double getNeighborMaxQPrime(int destAddr,
			Map<Integer, Double> encounterProbs) {
		if (destAddr < 0 || destAddr >= totalDest)
			return 0.0;

		double maxVal = 0.0;
		for (int y = 0; y < totalAction; y++) {
			double prob = encounterProbs.getOrDefault(y, 0.0);
			double val = qvalues[destAddr][y] * prob;
			if (val > maxVal)
				maxVal = val;
		}
		return maxVal;
	}

	/**
	 * Cek apakah Q-table untuk destination ini punya setidaknya satu
	 * entry bernilai > 0 (artinya node sudah pernah belajar tentang dest ini).
	 */
	public boolean hasQEntry(int destAddr) {
		if (destAddr < 0 || destAddr >= totalDest)
			return false;
		for (int x = 0; x < totalAction; x++) {
			if (qvalues[destAddr][x] > 0.0)
				return true;
		}
		return false;
	}

	/**
	 * Greedy: mengembalikan alamat node dengan Q-value tertinggi
	 * untuk destination tertentu. a* = argmax_x Qd(s,x).
	 *
	 * @return alamat relay terbaik, atau -1 jika destAddr invalid
	 */
	public int getBestAction(int destAddr) {
		if (destAddr < 0 || destAddr >= totalDest)
			return -1;

		int bestAction = 0;
		double bestVal = qvalues[destAddr][0];
		for (int x = 1; x < totalAction; x++) {
			if (qvalues[destAddr][x] > bestVal) {
				bestVal = qvalues[destAddr][x];
				bestAction = x;
			}
		}
		return bestAction;
	}

	// =========================================================================
	// CONNECTION HISTORY
	// =========================================================================

	/**
	 * Mencatat waktu mulai & akhir koneksi ke connHistory.
	 * Dipanggil oleh CCRouting.changedConnection() via super.
	 */
	@Override
	public void changedConnection(Connection con) {
		DTNHost peer = con.getOtherNode(getHost());

		if (con.isUp()) {
			startTimestamps.put(peer, SimClock.getTime());
		} else {
			if (startTimestamps.containsKey(peer)) {
				double start = startTimestamps.remove(peer);
				double end = SimClock.getTime();

				if (end - start > 0) {
					connHistory.computeIfAbsent(peer, k -> new LinkedList<>())
							.add(new Duration(start, end));
				}
			}
		}
	}

	// =========================================================================
	// MESSAGE CREATION (topic untuk InterestReport)
	// =========================================================================

	@Override
	public boolean createNewMessage(Message msg) {
		makeRoomForNewMessage(msg.getSize());
		msg.setTtl(this.msgTtl);

		List<Boolean> topics = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			topics.add(Math.random() < 0.5);
		}
		msg.addProperty(MESSAGE_TOPICS_S, topics);
		return super.createNewMessage(msg);
	}

	// =========================================================================
	// ABSTRACT
	// =========================================================================

	@Override
	public abstract QLearningRouter replicate();

	// update() di-override penuh oleh CCRouting; base tidak perlu logic khusus
	@Override
	public void update() {
		super.update();
	}

	// =========================================================================
	// GETTERS
	// =========================================================================

	public double getLearningRate() {
		return learningRate;
	}

	public double getDiscountFactor() {
		return discountFactor;
	}

	public double getAgingOmega() {
		return agingOmega;
	}
}