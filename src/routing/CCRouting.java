package routing;

import core.*;
import java.util.*;
import reinforcement.*;

/**
 * CCRouting — Implementasi ORQLCI sesuai paper:
 * "Opportunistic Routing using Q-Learning with Context Information"
 * Liu et al.
 *
 * ═══════════════════════════════════════════════════════════════
 * FLOW UTAMA (sesuai paper):
 *
 * [changedConnection — con.isUp()]
 * 1. Eq.1 — updateEncounterProb(other)
 * 2. Eq.3 — updateTransitivity(other)
 * 3. Alg.1 — updateQTableOnContact(other) ← langsung saat bertemu
 *
 * [changedConnection — con.isDown()]
 * 4. Hapus dari candidateReceiver
 *
 * [update() — setiap tick]
 * 5. exchangeDeliverableMessages() — direct delivery prioritas utama
 * 6. Alg.2 — tryOtherMessage() — forwarding via Q-table
 * 7. Periodik (updateInterval):
 * - Eq.11 — ageQTable() — aging Q-table
 *
 * ═══════════════════════════════════════════════════════════════
 * RIWAYAT PERBAIKAN:
 * [BUG-1] updateQRelay: BFx dikali dua kali → dynamicDiscount sudah = γ×BFx
 * [BUG-2] ageQTable: t selalu 1 → dihitung dari waktu nyata (Eq.11)
 * [BUG-3] updateTransitivity: preds routerB stale → age dulu sebelum baca
 * [BUG-4] Q-update ditunda ke interval → sekarang langsung di changedConnection
 * [BUG-5] ChooseAction untuk check forwarding → diganti getBestAction
 * [BUG-6] ε = 0.989 terlalu tinggi → hampir selalu random, bukan greedy
 * [BUG-7] Forwarding saat Q ada: ChooseAction check → logika greedy + epsilon
 * [BUG-8] Forwarding saat Q kosong: 1/200 prob → 50% prob eksplorasi
 * ═══════════════════════════════════════════════════════════════
 */
public class CCRouting extends QLearningRouter {

    // =========================================================================
    // SETTINGS KEYS
    // =========================================================================
    private static final String CCROUTING_NS = "CCRouting";
    private static final String UPDATE_INTERVAL_S = "updateInterval";
    private static final String TOTAL_STATE_S = "totalState";
    private static final String TOTAL_ACTION_S = "totalAction";
    private static final String BASE_GAMMA_S = "baseDiscountGamma";
    private static final String LEARNING_COEFF_S = "learningCoeff";

    // =========================================================================
    // PROPHET PARAMETERS (Section 3.1)
    // =========================================================================
    private static final double P_INIT = 0.75; // Pinit, Eq.1
    private static final double GAMMA_P = 0.98; // η decay factor, Eq.2
    private static final double BETA = 0.25; // β transitivity factor, Eq.3
    private static final int SEC_IN_TU = 30; // 1 time unit = 30 detik

    /** P(this, x): encounter probability node ini ke tiap node lain */
    private Map<DTNHost, Double> preds;
    private double lastAgeUpdate = 0.0;

    // =========================================================================
    // LEARNING PARAMETERS
    // =========================================================================
    /** γ konstan, dipakai untuk menghitung γd(s,x) = γ × BFx (Eq.7) */
    private double baseDiscountGamma;
    /** α awal; akan disesuaikan adaptif per encounter */
    private double learningCoeff;

    // =========================================================================
    // FUSION SCORE WEIGHTS
    // Dipakai untuk sorting kandidat relay (bukan untuk Q-update)
    // =========================================================================
    private static final double W_RL = 0.8;
    private static final double W_PROPHET = 0.1;
    private static final double W_BUFFER = 0.1;

    // =========================================================================
    // EXPLORATION POLICY
    // =========================================================================
    private EpsilonGreedyExploration epsilonGreedy;

    /**
     * [FIX-BUG-6] ε = 0.1 → 90% greedy, 10% eksplorasi.
     * Sebelumnya 0.989 → 98.9% random = tidak pernah greedy.
     * Nilai kecil ini lebih sesuai untuk fase exploitation setelah Q konvergen.
     */
    private static final double EPSILON = 0.1;

    /**
     * Probabilitas forward saat Q-table belum punya entry untuk destination.
     * [FIX-BUG-8] Sebelumnya 1/totalAction (~0.5%) → terlalu kecil.
     * Nilai 0.5 memberi kesempatan eksplorasi yang wajar di awal simulasi.
     */
    private static final double EXPLORE_PROB = 0.5;

    // =========================================================================
    // TIMING
    // =========================================================================
    private double updateInterval;
    private double lastUpdateTime = 0.0;

    // =========================================================================
    // DATA STRUCTURES
    // =========================================================================
    /** Koneksi aktif saat ini */
    private List<Connection> candidateReceiver;

    /**
     * pendingRewards: otherNodeAddr → List<destAddr>
     * Destination dari pesan yang sudah berhasil dikirim ke otherNode,
     * menunggu Q-update saat next encounter dengan node tersebut.
     */
    private Map<Integer, List<Integer>> pendingRewards;

    /**
     * visitCount: DTNHost → jumlah total encounter
     * Adaptive learning rate: α = learningCoeff / visitCount
     * Makin sering ketemu, α makin kecil → Q makin stabil
     */
    private Map<DTNHost, Integer> visitCount;

    // =========================================================================
    // CONSTRUCTOR
    // =========================================================================

    public CCRouting(Settings s) {
        super(s);
        Settings cc = new Settings(CCROUTING_NS);

        this.updateInterval = cc.getInt(UPDATE_INTERVAL_S);
        this.totalDest = cc.getInt(TOTAL_STATE_S); // = jumlah node
        this.totalAction = cc.getInt(TOTAL_ACTION_S); // = jumlah node
        this.baseDiscountGamma = cc.getDouble(BASE_GAMMA_S);
        this.learningCoeff = cc.getDouble(LEARNING_COEFF_S);

        this.learningRate = learningCoeff;
        this.discountFactor = baseDiscountGamma;
        this.agingOmega = GAMMA_P; // ω = η sesuai paper Section 3.2

        initQTable(); // reinit dengan totalDest & totalAction yang benar
        initPreds();
        initLocal();
    }

    protected CCRouting(CCRouting r) {
        super(r);
        this.updateInterval = r.updateInterval;
        this.baseDiscountGamma = r.baseDiscountGamma;
        this.learningCoeff = r.learningCoeff;

        initQTable();
        initPreds();
        initLocal();
    }

    private void initPreds() {
        this.preds = new LinkedHashMap<>();
        this.lastAgeUpdate = 0.0;
    }

    private void initLocal() {
        this.epsilonGreedy = new EpsilonGreedyExploration(EPSILON);
        this.candidateReceiver = new ArrayList<>();
        this.pendingRewards = new LinkedHashMap<>();
        this.visitCount = new LinkedHashMap<>();
    }

    // =========================================================================
    // ENCOUNTER PROBABILITY — Section 3.1
    // =========================================================================

    /**
     * Eq.2 — Decay P(this,x) berdasarkan waktu nyata yang berlalu.
     * P(a,b) = P(a,b)_old × η^t, t = Δtime / SEC_IN_TU
     * Dipanggil sebelum membaca atau menulis preds.
     */
    private void ageDeliveryPreds() {
        double now = SimClock.getTime();
        double timeDiff = (now - lastAgeUpdate) / SEC_IN_TU;
        if (timeDiff <= 0)
            return;

        double mult = Math.pow(GAMMA_P, timeDiff);
        for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
            e.setValue(e.getValue() * mult);
        }
        lastAgeUpdate = now;
    }

    /**
     * Eq.1 — Update P(this, other) saat bertemu other.
     * P(a,b) = P(a,b)_old + (1 - P(a,b)_old) × Pinit
     */
    private void updateEncounterProb(DTNHost other) {
        ageDeliveryPreds();
        double oldVal = preds.getOrDefault(other, 0.0);
        preds.put(other, oldVal + (1.0 - oldVal) * P_INIT);
    }

    /**
     * Eq.3 — Transitivity: update P(this, c) via node b.
     * P(a,c) = P(a,c)_old + (1 - P(a,c)_old) × P(a,b) × P(b,c) × β
     *
     * [FIX-BUG-3] routerB.ageDeliveryPreds() dipanggil dulu → P(b,c) fresh.
     */
    private void updateTransitivity(DTNHost nodeB, CCRouting routerB) {
        ageDeliveryPreds();
        routerB.ageDeliveryPreds(); // pastikan P(b,c) sudah up-to-date

        double pAB = preds.getOrDefault(nodeB, 0.0);
        if (pAB == 0.0)
            return;

        for (Map.Entry<DTNHost, Double> entry : routerB.preds.entrySet()) {
            DTNHost nodeC = entry.getKey();
            if (nodeC.equals(getHost()))
                continue;

            double pBC = entry.getValue();
            double pACold = preds.getOrDefault(nodeC, 0.0);
            preds.put(nodeC, pACold + (1.0 - pACold) * pAB * pBC * BETA);
        }
    }

    /** Membaca P(this, host) setelah di-age. */
    public double getPredFor(DTNHost host) {
        ageDeliveryPreds();
        return preds.getOrDefault(host, 0.0);
    }

    /**
     * Map<nodeAddress, P(this,node)> untuk semua node yang dikenal.
     * Dipakai oleh node tetangga untuk Eq.8.
     */
    public Map<Integer, Double> getEncounterProbMap() {
        ageDeliveryPreds();
        Map<Integer, Double> probMap = new HashMap<>();
        for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
            probMap.put(e.getKey().getAddress(), e.getValue());
        }
        return probMap;
    }

    // =========================================================================
    // BUFFER FACTOR — Eq.4
    // =========================================================================

    /**
     * Eq.4 — Rasio buffer yang masih bebas.
     * BF = 1 - (Σ Bm) / Cinit
     * BF tinggi = buffer lega = node layak jadi relay.
     */
    private double getBufferFactor(DTNHost host) {
        MessageRouter router = host.getRouter();
        int cTotal = router.getBufferSize();
        if (cTotal <= 0 || cTotal == Integer.MAX_VALUE)
            return 1.0;

        long occupied = 0;
        for (Message m : router.getMessageCollection()) {
            occupied += m.getSize();
        }
        return Math.max(0.0, Math.min(1.0, 1.0 - (double) occupied / cTotal));
    }

    // =========================================================================
    // FUSION SCORE
    // =========================================================================

    /**
     * Fusion score = 0.8×RL + 0.1×ProphetDelta + 0.1×BufferFactor
     * Dipakai HANYA untuk sorting prioritas pesan yang sudah lolos seleksi Q.
     * Tidak terlibat dalam Q-update.
     */
    private double getFusionScore(Message m, DTNHost other) {
        int destAddr = m.getTo().getAddress();
        double rlScore = getQV(destAddr, other.getAddress());
        double prophetDelta = getPredFor(other) - getPredFor(m.getTo());
        double bf = getBufferFactor(other);

        return (W_RL * rlScore)
                + (W_PROPHET * Math.max(0.0, prophetDelta))
                + (W_BUFFER * bf);
    }

    // =========================================================================
    // Q-TABLE UPDATE SAAT KONEKSI — Algorithm 1
    // =========================================================================

    /**
     * Algorithm 1 — Update Q-table segera saat bertemu node other.
     *
     * Untuk tiap destination d di pendingRewards[other]:
     * other == d → Eq.10 (direct, reward = 1)
     * other != d → Eq.9 (relay, reward = 0, pakai neighborMaxQPrime)
     *
     * [FIX-BUG-4] Dipanggil langsung dari changedConnection (con.isUp),
     * bukan ditunda ke interval periodik.
     *
     * Adaptive α: α = learningCoeff / visitCount, min 0.01
     */
    private void updateQTableOnContact(DTNHost other, CCRouting otherRouter) {
        int otherAddr = other.getAddress();

        List<Integer> dests = pendingRewards.get(otherAddr);
        if (dests == null || dests.isEmpty())
            return;

        // Adaptive learning rate
        int visits = visitCount.getOrDefault(other, 0) + 1;
        visitCount.put(other, visits);
        this.learningRate = Math.max(0.01, learningCoeff / visits);

        double bfOther = getBufferFactor(other);

        // Eq.7 — γd(s,x) = γ × BFx
        double dynamicDiscount = baseDiscountGamma * bfOther;

        // Eq.8 — encounter prob map dari other
        Map<Integer, Double> otherProbMap = otherRouter.getEncounterProbMap();

        for (int destAddr : dests) {
            if (otherAddr == destAddr) {
                // Eq.10 — direct delivery
                updateQDirect(destAddr, otherAddr);
            } else {
                // Eq.9 — relay
                // [FIX-BUG-1] dynamicDiscount = γ×BFx, tidak dikali BFx lagi
                double neighborMaxQP = otherRouter.getNeighborMaxQPrime(destAddr, otherProbMap);
                updateQRelay(destAddr, otherAddr, dynamicDiscount, neighborMaxQP);
            }
        }
        dests.clear();
    }

    // =========================================================================
    // CONNECTION HANDLER
    // =========================================================================

    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);

        DTNHost other = con.getOtherNode(getHost());
        CCRouting otherRouter = (CCRouting) other.getRouter();

        if (con.isUp()) {
            candidateReceiver.add(con);
            pendingRewards.putIfAbsent(other.getAddress(), new ArrayList<>());

            // Eq.1 — update encounter probability
            updateEncounterProb(other);

            // Eq.3 — update transitivity
            updateTransitivity(other, otherRouter);

            // Algorithm 1 — Q-update langsung saat koneksi naik
            updateQTableOnContact(other, otherRouter);

        } else {
            candidateReceiver.remove(con);
        }
    }

    // =========================================================================
    // UPDATE LOOP
    // =========================================================================

    @Override
    public void update() {
        super.update();

        if (isTransferring() || !canStartTransfer())
            return;

        // Prioritas 1: kirim langsung ke destination
        if (exchangeDeliverableMessages() != null)
            return;

        // Prioritas 2: relay via Algorithm 2
        tryOtherMessage();

        // Periodik: aging Q-table (Eq.11)
        double now = SimClock.getTime();
        if ((now - lastUpdateTime) >= updateInterval) {
            lastUpdateTime = now;
            // [FIX-BUG-2] t dihitung dari waktu nyata
            ageQTable(SEC_IN_TU);
        }
    }

    // =========================================================================
    // MESSAGE FORWARDING — Algorithm 2
    // =========================================================================

    /**
     * Algorithm 2 — Memilih relay terbaik untuk setiap pesan.
     *
     * Kasus A — Q-table PUNYA entry untuk dest(m):
     * → getBestAction(): argmax Qd(s,x) = relay terbaik menurut Q
     * → Forward ke other JIKA other == bestAction (greedy)
     * → Dengan prob EPSILON, forward ke other walau bukan bestAction (eksplorasi)
     * [FIX-BUG-6,7] ε = 0.1 → 90% greedy, 10% eksplorasi
     *
     * Kasus B — Q-table BELUM punya entry untuk dest(m):
     * → Forward ke other dengan prob EXPLORE_PROB = 50%
     * [FIX-BUG-8] Sebelumnya 1/200 = 0.5% → terlalu kecil untuk eksplorasi
     *
     * Dari semua kandidat yang lolos, pilih fusion score tertinggi.
     */
    private void tryOtherMessage() {
        Collection<Message> msgCollection = getMessageCollection();
        if (msgCollection.isEmpty() || candidateReceiver.isEmpty())
            return;

        for (Connection con : candidateReceiver) {
            DTNHost other = con.getOtherNode(getHost());
            CCRouting otherRouter = (CCRouting) other.getRouter();
            if (otherRouter.isTransferring())
                continue;

            List<Tuple<Message, Connection>> potentials = new ArrayList<>();

            for (Message m : msgCollection) {
                if (otherRouter.hasMessage(m.getId()))
                    continue;
                if (otherRouter.getFreeBufferSize() < m.getSize())
                    continue;

                int destAddr = m.getTo().getAddress();
                boolean shouldForward = false;

                if (hasQEntry(destAddr)) {
                    // Kasus A: Q sudah ada → greedy dengan eksplorasi kecil
                    int bestAction = getBestAction(destAddr);

                    if (bestAction == other.getAddress()) {
                        // other adalah relay terbaik menurut Q → forward
                        shouldForward = true;
                    } else if (Math.random() < EPSILON) {
                        // Eksplorasi: coba other walau bukan yang terbaik
                        shouldForward = true;
                    }
                } else {
                    // Kasus B: Q belum ada → eksplorasi probabilistik
                    if (Math.random() < EXPLORE_PROB) {
                        shouldForward = true;
                    }
                }

                if (shouldForward) {
                    potentials.add(new Tuple<>(m, con));
                }
            }

            if (!potentials.isEmpty()) {
                // Sort berdasarkan fusion score tertinggi
                potentials.sort((t1, t2) -> {
                    double fs1 = getFusionScore(
                            t1.getKey(), t1.getValue().getOtherNode(getHost()));
                    double fs2 = getFusionScore(
                            t2.getKey(), t2.getValue().getOtherNode(getHost()));
                    return Double.compare(fs2, fs1);
                });

                Tuple<Message, Connection> best = potentials.get(0);
                if (startTransfer(best.getKey(), best.getValue()) == MessageRouter.RCV_OK) {
                    int otherAddr = other.getAddress();
                    int destAddr = best.getKey().getTo().getAddress();
                    pendingRewards.get(otherAddr).add(destAddr);
                    break; // satu transfer per update cycle
                }
            }
        }
    }

    // =========================================================================
    // REPLICATE
    // =========================================================================

    @Override
    public CCRouting replicate() {
        return new CCRouting(this);
    }
}