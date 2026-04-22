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
 * QLearningRouter â€” Abstract base class untuk ORQLCI routing.
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
 *
 * â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
  * Catatan:
  * Implementasi aging Eq.11 dilakukan per-entry Q[d][x] (berdasarkan timestamp
  * terakhir entry tersebut di-update/di-age), bukan satu timestamp global.
 * â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
 */
public abstract class QLearningRouter extends ActiveRouter {

	public static final String MESSAGE_TOPICS_S = "topic";

	// -------------------------------------------------------------------------
	// Q-TABLE
	// Dimensi: qvalues[d][x] â‰¡ Qd(s, x) di paper
	// d = alamat destination node (0 .. totalDest-1)
	// x = alamat relay/action node (0 .. totalAction-1)
	// State 's' pada Qd(s,x) adalah node saat ini (sn). Karena tiap node menyimpan
	// Q-table miliknya sendiri, dimensi 's' tidak menjadi indeks tabel eksplisit.
	// -------------------------------------------------------------------------
	protected double[][] qvalues;
	protected int totalDest; // diisi oleh CCRouting dari config totalState
	protected int totalAction; // diisi oleh CCRouting dari config totalAction

	// -------------------------------------------------------------------------
	// LEARNING PARAMETERS â€” nilai default sesuai paper Section 4.1
	// -------------------------------------------------------------------------
	protected double learningRate = 0.8; // Î±
	protected double discountFactor = 0.6; // Î³ (nilai statis; Î³d dihitung di CCRouting)
	protected double agingOmega = 0.98; // Ï‰ untuk Eq.11

	/**
	 * Timestamp terakhir setiap entry Q[d][x] di-update/di-age.
	 * Paper mendefinisikan t pada Eq.11 per gossip entry, bukan satu global
	 * timestamp untuk seluruh tabel.
	 */
	protected double[][] lastQUpdateTimes;
	protected int qAgeTimeUnit = 30;

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
		this.totalDest = 5;
		this.totalAction = 5;
		initQTable();
	}

	protected QLearningRouter(QLearningRouter r) {
		super(r);
		this.startTimestamps = new HashMap<>();
		this.connHistory = new HashMap<>();
		this.totalDest = r.totalDest;
		this.totalAction = r.totalAction;
		this.learningRate = r.learningRate;
		this.discountFactor = r.discountFactor;
		this.agingOmega = r.agingOmega;
		this.qAgeTimeUnit = r.qAgeTimeUnit;
		initQTable();
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
		lastQUpdateTimes = new double[totalDest][];
		for (int i = 0; i < totalDest; i++) {
			qvalues[i] = new double[totalAction];
			lastQUpdateTimes[i] = new double[totalAction];
		}
	}

	private boolean isValidDest(int destAddr) {
		return destAddr >= 0 && destAddr < totalDest;
	}

	private boolean isValidAction(int actionAddr) {
		return actionAddr >= 0 && actionAddr < totalAction;
	}

	private void ageQEntry(int destAddr, int actionAddr) {
		ageQEntry(destAddr, actionAddr, qAgeTimeUnit);
	}

	private void ageQEntry(int destAddr, int actionAddr, int secInTimeUnit) {
		if (!isValidDest(destAddr) || !isValidAction(actionAddr) || secInTimeUnit <= 0) {
			return;
		}

		double now = SimClock.getTime();
		double lastUpdate = lastQUpdateTimes[destAddr][actionAddr];
		double timeDiff = (now - lastUpdate) / secInTimeUnit;
		if (timeDiff <= 0) {
			return;
		}

		qvalues[destAddr][actionAddr] *= Math.pow(agingOmega, timeDiff);
		lastQUpdateTimes[destAddr][actionAddr] = now;
	}

	/**
	 * Membaca Qd(s,x) = qvalues[destAddr][actionAddr].
	 * Return 0.0 jika indeks di luar batas.
	 */
	public double getQV(int destAddr, int actionAddr) {
		if (!isValidDest(destAddr) || !isValidAction(actionAddr))
			return 0.0;
		ageQEntry(destAddr, actionAddr);
		return qvalues[destAddr][actionAddr];
	}

	/**
	 * Eq.10 â€” Update Q saat encountered node x ADALAH destination d.
	 *
	 * Qd(s,x) â† (1-Î±) Ã— Qd(s,x) + Î± Ã— Rd(s,x)
	 * Rd(s,x) = 1 (karena x == d)
	 *
	 * @param destAddr  alamat d (destination)
	 * @param relayAddr alamat x (== destAddr dalam kasus ini)
	 */
	public void updateQDirect(int destAddr, int relayAddr) {
		if (!isValidDest(destAddr) || !isValidAction(relayAddr))
			return;

		ageQEntry(destAddr, relayAddr);
		double oldQ = qvalues[destAddr][relayAddr];
		// Eq.10: reward = 1, tidak ada discount term
		qvalues[destAddr][relayAddr] = (1.0 - learningRate) * oldQ
				+ learningRate * 1.0;
		lastQUpdateTimes[destAddr][relayAddr] = SimClock.getTime();
	}

	/**
	 * Eq.9 â€” Update Q saat encountered node x BUKAN destination d.
	 *
	 * Qd(s,x) â† (1-Î±) Ã— Qd(s,x) + Î± Ã— Î³d(s,x) Ã— max_y(Qd(x,y)Ã—P(x,y))
	 *
	 * Î³d(s,x) = Î³ Ã— BFx (Eq.7) sudah dihitung oleh CCRouting â†’ dynamicDiscount.
	 * TIDAK ada perkalian BFx lagi di sini (sudah termasuk dalam dynamicDiscount).
	 *
	 * @param destAddr        alamat d
	 * @param relayAddr       alamat x (relay, bukan destination)
	 * @param dynamicDiscount Î³d(s,x) = Î³ Ã— BFx (hasil Eq.7)
	 * @param neighborMaxQP   max_y(Qd(x,y)Ã—P(x,y)) (hasil Eq.8)
	 */
	public void updateQRelay(int destAddr, int relayAddr,
			double dynamicDiscount, double neighborMaxQP) {
		if (!isValidDest(destAddr) || !isValidAction(relayAddr))
			return;

		ageQEntry(destAddr, relayAddr);
		double oldQ = qvalues[destAddr][relayAddr];
		// Eq.9: reward = 0, suku reward gugur
		qvalues[destAddr][relayAddr] = (1.0 - learningRate) * oldQ
				+ learningRate * dynamicDiscount * neighborMaxQP;
		lastQUpdateTimes[destAddr][relayAddr] = SimClock.getTime();
	}

	/**
	 * Eq.11 â€” Aging seluruh Q-Table berdasarkan waktu nyata yang berlalu.
	 *
	 * Qd(s,x) = Qd(s,x)_old Ã— Ï‰^t
	 * t = (now - lastQAgeTime) / secInTimeUnit
	 *
	 * Dapat dipanggil kapan saja: di awal encounter (Alg.1) dan periodik
	 * dari CCRouting.update(). lastQAgeTime diperbarui setelah aging.
	 *
	 * @param secInTimeUnit satuan waktu (detik per time unit)
	 */
	public void ageQTable(int secInTimeUnit) {
		if (secInTimeUnit <= 0)
			return;

		this.qAgeTimeUnit = secInTimeUnit;
		for (int d = 0; d < totalDest; d++) {
			for (int x = 0; x < totalAction; x++) {
				ageQEntry(d, x, secInTimeUnit);
			}
		}
	}

	/**
	 * Eq.8 â€” Menghitung max_yâˆˆNx [ Qd(x,y) Ã— P(x,y) ].
	 *
	 * Dipanggil oleh node x (router tetangga) untuk menyediakan data
	 * yang dibutuhkan node s dalam Eq.9.
	 *
	 * @param destAddr       alamat destination d
	 * @param encounterProbs Map<nodeAddress, P(x,y)> milik node x
	 * @return nilai maksimum Qd(x,y) Ã— P(x,y) di antara semua y yang dikenal
	 */
	public double getNeighborMaxQPrime(int destAddr,
			Map<Integer, Double> encounterProbs) {
		if (!isValidDest(destAddr))
			return 0.0;

		double maxVal = 0.0;
		for (int y = 0; y < totalAction; y++) {
			double prob = encounterProbs.getOrDefault(y, 0.0);
			double val = getQV(destAddr, y) * prob;
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
		if (!isValidDest(destAddr))
			return false;
		for (int x = 0; x < totalAction; x++) {
			if (getQV(destAddr, x) > 0.0)
				return true;
		}
		return false;
	}

	/**
	 * Greedy: mengembalikan alamat node dengan Q-value tertinggi
	 * untuk destination tertentu.
	 * a* = argmax_x Qd(s, x)
	 *
	 * @return alamat relay terbaik (index), atau -1 jika destAddr invalid
	 */
	public int getBestAction(int destAddr) {
		if (!isValidDest(destAddr))
			return -1;

		int bestAction = 0;
		double bestVal = getQV(destAddr, 0);
		for (int x = 1; x < totalAction; x++) {
			double qValue = getQV(destAddr, x);
			if (qValue > bestVal) {
				bestVal = qValue;
				bestAction = x;
			}
		}
		return bestAction;
	}

	/**
	 * Mengembalikan Q-value terbaik untuk destination tertentu.
	 * Berguna untuk gradient check di Alg.2.
	 *
	 * @return max Qd(s,x) di antara semua x, atau 0.0 jika invalid
	 */
	public double getBestQValue(int destAddr) {
		if (!isValidDest(destAddr))
			return 0.0;
		double best = 0.0;
		for (int x = 0; x < totalAction; x++) {
			double qValue = getQV(destAddr, x);
			if (qValue > best)
				best = qValue;
		}
		return best;
	}

	// =========================================================================
	// CONNECTION HISTORY
	// =========================================================================

	/**
	 * Mencatat waktu mulai & akhir koneksi ke connHistory.
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
