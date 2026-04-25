package routing;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import routing.community.Duration;

/**
 * Base router yang mengimplementasikan Q-Learning untuk Opportunistic Networks.
 * 
 * Q-Table Structure:
 * - Dynamic HashMap: qvalues[destAddr][actionAddr] = Q-value
 * - destAddr: alamat destination node (0 hingga max nodes)
 * - actionAddr: alamat relay/action node (0 hingga max nodes)
 * - State 's' implicit (setiap node punya Q-table sendiri)
 * 
 * Sesuai paper ORQLCI Section 3.2:
 * - Eq. 9: Update Q saat relay bukan destination
 * - Eq. 10: Update Q saat relay adalah destination
 * - Eq. 11: Q-value aging over time
 */
public abstract class QLearningRouter extends ActiveRouter {

	public static final String MESSAGE_TOPICS_S = "topic";

	// =========================================================================
	// Q-TABLE - DYNAMIC STRUCTURE (FIX BUG #1)
	// =========================================================================

	/**
	 * Q-Table: qvalues[destAddr][actionAddr] = Qd(s,x)
	 * - Outer map: destination address → inner map
	 * - Inner map: action/relay address → Q-value
	 * 
	 * Menggunakan HashMap untuk:
	 * 1. Memory efficiency (hanya store yang dipakai)
	 * 2. Scalability (tidak perlu tahu max nodes di awal)
	 * 3. Sesuai paper: "dynamic Q-table"
	 */
	protected Map<Integer, Map<Integer, Double>> qvalues;

	/**
	 * Timestamp terakhir setiap entry Q[d][x] di-update/di-age.
	 * Structure sama dengan qvalues untuk consistency.
	 */
	protected Map<Integer, Map<Integer, Double>> lastQUpdateTimes;

	// =========================================================================
	// LEARNING PARAMETERS (sesuai paper Section 4.1)
	// =========================================================================

	protected double learningRate = 0.8; // α - learning coefficient
	protected double discountFactor = 0.6; // γ - base discount factor
	protected double agingOmega = 0.98; // ω - aging constant (Eq. 11)
	protected int qAgeTimeUnit = 30; // Time unit untuk aging (30s)

	// =========================================================================
	// CONNECTION HISTORY
	// =========================================================================

	protected Map<DTNHost, Double> startTimestamps;
	protected Map<DTNHost, List<Duration>> connHistory;

	// =========================================================================
	// CONSTRUCTOR
	// =========================================================================

	public QLearningRouter(Settings s) {
		super(s);
		this.startTimestamps = new HashMap<>();
		this.connHistory = new HashMap<>();
		initQTable();
	}

	protected QLearningRouter(QLearningRouter r) {
		super(r);
		this.startTimestamps = new HashMap<>();
		this.connHistory = new HashMap<>();
		this.learningRate = r.learningRate;
		this.discountFactor = r.discountFactor;
		this.agingOmega = r.agingOmega;
		this.qAgeTimeUnit = r.qAgeTimeUnit;
		initQTable();
	}

	// =========================================================================
	// Q-TABLE OPERATIONS (FIXED!)
	// =========================================================================

	/**
	 * Inisialisasi Q-Table sebagai dynamic HashMap.
	 * Tidak perlu parameter totalDest/totalAction!
	 */
	protected void initQTable() {
		qvalues = new HashMap<>();
		lastQUpdateTimes = new HashMap<>();
	}

	/**
	 * Memastikan entry untuk (destAddr, actionAddr) ada di Q-table.
	 * Jika belum ada, buat dengan nilai default 0.0.
	 * 
	 * @param destAddr   destination address
	 * @param actionAddr action/relay address
	 */
	private void ensureQEntry(int destAddr, int actionAddr) {
		qvalues.putIfAbsent(destAddr, new HashMap<>());
		lastQUpdateTimes.putIfAbsent(destAddr, new HashMap<>());

		qvalues.get(destAddr).putIfAbsent(actionAddr, 0.0);
		lastQUpdateTimes.get(destAddr).putIfAbsent(actionAddr, 0.0);
	}

	/**
	 * Validasi alamat node (harus non-negative).
	 */
	private boolean isValidAddress(int addr) {
		return addr >= 0;
	}

	/**
	 * Age satu entry Q-table berdasarkan waktu yang berlalu.
	 * Hanya dipanggil pada entry yang SUDAH ADA — tidak membuat entry baru.
	 *
	 * Eq. 11 (paper): Qd(s,x) = Qd(s,x)_old × ω^t
	 * di mana t = (now - lastUpdate) / timeUnit
	 *
	 * @param destAddr      destination address
	 * @param actionAddr    action address
	 * @param secInTimeUnit detik per time unit
	 */
	private void ageQEntry(int destAddr, int actionAddr, int secInTimeUnit) {
		if (!isValidAddress(destAddr) || !isValidAddress(actionAddr)) {
			return;
		}
		if (secInTimeUnit <= 0) {
			return;
		}

		// Guard: hanya age entry yang sudah ada, jangan buat entry baru
		Map<Integer, Double> actionMap = qvalues.get(destAddr);
		if (actionMap == null || !actionMap.containsKey(actionAddr)) {
			return;
		}

		double now = SimClock.getTime();
		double lastUpdate = lastQUpdateTimes.get(destAddr).get(actionAddr);
		double timeDiff = (now - lastUpdate) / secInTimeUnit;

		if (timeDiff <= 0) {
			return;
		}

		// Eq. 11: Apply aging
		double currentQ = actionMap.get(actionAddr);
		double agedQ = currentQ * Math.pow(agingOmega, timeDiff);

		actionMap.put(actionAddr, agedQ);
		lastQUpdateTimes.get(destAddr).put(actionAddr, now);
	}

	/**
	 * Membaca Q-value: Qd(s,x).
	 * Hanya meng-age entry yang sudah ada; TIDAK membuat entry baru.
	 * Jika entry belum ada, return 0.0 tanpa side effect.
	 *
	 * @param destAddr   destination address
	 * @param actionAddr action/relay address
	 * @return Q-value ter-age, atau 0.0 jika belum ada entry
	 */
	public double getQV(int destAddr, int actionAddr) {
		if (!isValidAddress(destAddr) || !isValidAddress(actionAddr)) {
			return 0.0;
		}

		// Jika entry belum ada, return 0.0 tanpa menciptakan entry baru
		Map<Integer, Double> actionMap = qvalues.get(destAddr);
		if (actionMap == null || !actionMap.containsKey(actionAddr)) {
			return 0.0;
		}

		// Age entry yang sudah ada, lalu return
		ageQEntry(destAddr, actionAddr, qAgeTimeUnit);
		return qvalues.get(destAddr).get(actionAddr);
	}

	/**
	 * Update Q-value saat encountered node x ADALAH destination d.
	 *
	 * Eq. 10 (paper):
	 * Qd(s,x) ← (1-α) × Qd(s,x) + α × Rd(s,x)
	 * di mana Rd(s,x) = 1 (karena x == d)
	 *
	 * Aging dilakukan sekali di sini — TIDAK boleh dipanggil setelah
	 * ageQTable() sudah meng-age entry yang sama dalam satu cycle.
	 *
	 * @param destAddr  alamat destination d
	 * @param relayAddr alamat relay x (sama dengan destAddr)
	 */
	public void updateQDirect(int destAddr, int relayAddr) {
		if (!isValidAddress(destAddr) || !isValidAddress(relayAddr)) {
			return;
		}

		// Pastikan entry ada sebelum update
		ensureQEntry(destAddr, relayAddr);

		// Baca Q-value yang sudah ter-age (ageQEntry sudah dipanggil oleh ageQTable sebelumnya)
		double oldQ = qvalues.get(destAddr).get(relayAddr);

		// Eq. 10: reward = 1
		double newQ = (1.0 - learningRate) * oldQ + learningRate * 1.0;

		qvalues.get(destAddr).put(relayAddr, newQ);
		lastQUpdateTimes.get(destAddr).put(relayAddr, SimClock.getTime());
	}

	/**
	 * Update Q-value saat encountered node x BUKAN destination d.
	 *
	 * Eq. 9 (paper):
	 * Qd(s,x) ← (1-α) × Qd(s,x) + α × γd(s,x) × max_y[Qd(x,y)×P(x,y)]
	 *
	 * di mana:
	 * - γd(s,x) = γ × BFx (Eq. 7) → dynamicDiscount
	 * - max_y[Qd(x,y)×P(x,y)] (Eq. 8) → neighborMaxQP
	 *
	 * Aging dilakukan sekali di sini — TIDAK boleh dipanggil setelah
	 * ageQTable() sudah meng-age entry yang sama dalam satu cycle.
	 *
	 * @param destAddr        alamat destination d
	 * @param relayAddr       alamat relay x (bukan destination)
	 * @param dynamicDiscount γd(s,x) = γ × BFx dari Eq. 7
	 * @param neighborMaxQP   max_y[Qd(x,y)×P(x,y)] dari Eq. 8
	 */
	public void updateQRelay(int destAddr, int relayAddr,
			double dynamicDiscount, double neighborMaxQP) {

		if (!isValidAddress(destAddr) || !isValidAddress(relayAddr)) {
			return;
		}

		// Pastikan entry ada sebelum update
		ensureQEntry(destAddr, relayAddr);

		// Baca Q-value yang sudah ter-age (ageQEntry sudah dipanggil oleh ageQTable sebelumnya)
		double oldQ = qvalues.get(destAddr).get(relayAddr);

		// Eq. 9
		double newQ = (1.0 - learningRate) * oldQ
				+ learningRate * dynamicDiscount * neighborMaxQP;

		qvalues.get(destAddr).put(relayAddr, newQ);
		lastQUpdateTimes.get(destAddr).put(relayAddr, SimClock.getTime());
	}

	/**
	 * Age seluruh Q-Table (semua entries yang sudah ada).
	 * Tidak mengubah qAgeTimeUnit — tidak ada side effect pada state.
	 *
	 * @param secInTimeUnit detik per time unit untuk aging
	 */
	public void ageQTable(int secInTimeUnit) {
		if (secInTimeUnit <= 0) {
			return;
		}

		// Iterate semua entries yang ada — tidak membuat entry baru
		for (Map.Entry<Integer, Map<Integer, Double>> destEntry : qvalues.entrySet()) {
			int destAddr = destEntry.getKey();
			for (Integer actionAddr : destEntry.getValue().keySet()) {
				ageQEntry(destAddr, actionAddr, secInTimeUnit);
			}
		}
	}

	/**
	 * Menghitung max_y∈Nx [Qd(x,y) × P(x,y)] (Eq. 8).
	 * Membaca Q-table milik THIS node (node x / relay).
	 * Dipanggil via otherRouter.getNeighborMaxQPrime() dari node s.
	 *
	 * @param destAddr       alamat destination d
	 * @param encounterProbs Map<neighborAddr, P(x,neighbor)> milik node x
	 * @return nilai maksimum Qd(x,y) × P(x,y), atau 0.0 jika tidak ada data
	 */
	public double getNeighborMaxQPrime(int destAddr,
			Map<Integer, Double> encounterProbs) {

		if (!isValidAddress(destAddr)) {
			return 0.0;
		}
		if (encounterProbs == null || encounterProbs.isEmpty()) {
			return 0.0;
		}

		double maxVal = 0.0;

		for (Map.Entry<Integer, Double> entry : encounterProbs.entrySet()) {
			int neighborAddr = entry.getKey();
			double prob = entry.getValue();

			if (!isValidAddress(neighborAddr)) {
				continue;
			}

			// getQV tidak membuat entry baru jika belum ada — aman
			double qVal = getQV(destAddr, neighborAddr);
			double val = qVal * prob;

			if (val > maxVal) {
				maxVal = val;
			}
		}

		return maxVal;
	}

	/**
	 * Cek apakah ada Q-entry dengan nilai > 0 untuk destination tertentu.
	 * Membaca langsung dari map tanpa memanggil getQV() untuk menghindari
	 * side effect aging yang tidak perlu saat hanya ingin cek keberadaan.
	 *
	 * @param destAddr destination address
	 * @return true jika ada minimal 1 action dengan Q > 0 (setelah aging)
	 */
	public boolean hasQEntry(int destAddr) {
		if (!isValidAddress(destAddr)) {
			return false;
		}

		Map<Integer, Double> actionMap = qvalues.get(destAddr);
		if (actionMap == null || actionMap.isEmpty()) {
			return false;
		}

		// Cek apakah ada action dengan Q > 0 setelah aging
		for (Map.Entry<Integer, Double> entry : actionMap.entrySet()) {
			int actionAddr = entry.getKey();
			// Age entry dulu sebelum cek nilainya
			ageQEntry(destAddr, actionAddr, qAgeTimeUnit);
			if (qvalues.get(destAddr).get(actionAddr) > 0.0) {
				return true;
			}
		}

		return false;
	}

	/**
	 * Greedy action selection: a* = argmax_x Qd(s, x).
	 * Membaca langsung dari map dan age entry yang ada.
	 *
	 * @param destAddr destination address
	 * @return alamat relay terbaik, atau -1 jika tidak ada entry
	 */
	public int getBestAction(int destAddr) {
		if (!isValidAddress(destAddr)) {
			return -1;
		}

		Map<Integer, Double> actionMap = qvalues.get(destAddr);
		if (actionMap == null || actionMap.isEmpty()) {
			return -1;
		}

		int bestAction = -1;
		double bestVal = Double.NEGATIVE_INFINITY;

		for (Map.Entry<Integer, Double> entry : actionMap.entrySet()) {
			int actionAddr = entry.getKey();
			// Age entry sebelum baca — tidak membuat entry baru
			ageQEntry(destAddr, actionAddr, qAgeTimeUnit);
			double qVal = qvalues.get(destAddr).get(actionAddr);

			if (qVal > bestVal) {
				bestVal = qVal;
				bestAction = actionAddr;
			}
		}

		return bestAction;
	}

	/**
	 * Mendapatkan Q-value terbaik untuk destination tertentu.
	 *
	 * @param destAddr destination address
	 * @return max Qd(s,x) di antara semua x yang ada
	 */
	public double getBestQValue(int destAddr) {
		if (!isValidAddress(destAddr)) {
			return 0.0;
		}

		Map<Integer, Double> actionMap = qvalues.get(destAddr);
		if (actionMap == null || actionMap.isEmpty()) {
			return 0.0;
		}

		double bestVal = 0.0;

		for (Map.Entry<Integer, Double> entry : actionMap.entrySet()) {
			int actionAddr = entry.getKey();
			ageQEntry(destAddr, actionAddr, qAgeTimeUnit);
			double qVal = qvalues.get(destAddr).get(actionAddr);

			if (qVal > bestVal) {
				bestVal = qVal;
			}
		}

		return bestVal;
	}

	/**
	 * Mendapatkan semua actions yang tersedia untuk destination tertentu.
	 * 
	 * @param destAddr destination address
	 * @return list of action addresses, atau empty list
	 */
	public List<Integer> getAvailableActions(int destAddr) {
		List<Integer> actions = new ArrayList<>();

		if (!isValidAddress(destAddr)) {
			return actions;
		}

		if (!qvalues.containsKey(destAddr)) {
			return actions;
		}

		actions.addAll(qvalues.get(destAddr).keySet());
		return actions;
	}

	// =========================================================================
	// CONNECTION HISTORY
	// =========================================================================

	/**
	 * Mencatat waktu mulai & akhir koneksi.
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
	// MESSAGE CREATION
	// =========================================================================

	@Override
	public boolean createNewMessage(Message msg) {
		makeRoomForNewMessage(msg.getSize());
		msg.setTtl(this.msgTtl);

		// Add random topics untuk InterestReport (jika dipakai)
		List<Boolean> topics = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			topics.add(Math.random() < 0.5);
		}
		msg.addProperty(MESSAGE_TOPICS_S, topics);

		return super.createNewMessage(msg);
	}

	// =========================================================================
	// ABSTRACT & OVERRIDES
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

	/**
	 * Debug: print Q-table statistics.
	 */
	public String getQTableStats() {
		int totalDests = qvalues.size();
		int totalEntries = 0;
		double totalQValue = 0.0;

		for (Map<Integer, Double> actionMap : qvalues.values()) {
			totalEntries += actionMap.size();
			for (Double qVal : actionMap.values()) {
				totalQValue += qVal;
			}
		}

		double avgQ = totalEntries > 0 ? totalQValue / totalEntries : 0.0;

		return String.format("Q-Table: %d dests, %d entries, avg Q=%.4f",
				totalDests, totalEntries, avgQ);
	}
}