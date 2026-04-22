package routing;

import core.*;
import java.util.*;

/**
 * CCRouting — Implementasi ORQLCI sesuai paper (Conservative Version):
 * "Opportunistic Routing using Q-Learning with Context Information"
 * Liu et al.
 *
 * ═══════════════════════════════════════════════════════════════
 * PRINSIP KONSERVATIF:
 * - Implementasi mengikuti paper secara literal, tanpa interpretasi berlebihan
 * - Q-update HANYA untuk destination pesan yang ada di buffer (on-policy)
 * - updateQTableOnContact() HANYA dipanggil saat changedConnection
 * - Alg.2 "Update Q-Table" diinterpretasi sebagai "gunakan Q yang sudah ada"
 * - PRoPHET fallback permisif (>=) untuk sparse network
 * ═══════════════════════════════════════════════════════════════
 *
 * FLOW SESUAI PAPER:
 *
 * [changedConnection — con.isUp()]
 * 1. Eq.1 — updateEncounterProb(other)
 * 2. Eq.3 — updateTransitivity(other)
 * 3. Alg.1 — updateQTableOnContact(other):
 * - Eq.11 aging Q dulu
 * - Eq.9/10 update Q untuk dest pesan yang DI BUFFER
 *
 * [update() — setiap tick]
 * 4. exchangeDeliverableMessages() — direct delivery prioritas
 * 5. Alg.2 — tryOtherMessage():
 * - Greedy: a* = argmax Qd(s,x) jika Q ada
 * - PRoPHET: ascending gradient jika Q belum ada
 * 6. Periodik: Eq.11 aging Q-table
 *
 * ═══════════════════════════════════════════════════════════════
 * VERIFIKASI PERSAMAAN PAPER:
 *
 * Eq.1 ✓ P(a,b) = P_old + (1-P_old)×Pinit
 * Eq.2 ✓ P(a,b) = P_old × η^t
 * Eq.3 ✓ P(a,c) = P_old + (1-P_old)×P(a,b)×P(b,c)×β
 * Eq.4 ✓ BF = 1 - Σ(Bm)/Cinit
 * Eq.6 ✓ Reward: 1 jika direct, 0 jika relay
 * Eq.7 ✓ γd(s,x) = γ × BFx
 * Eq.8 ✓ max_y[Qd(x,y)×P(x,y)]
 * Eq.9 ✓ Q relay update: (1-α)Q + α×γd×maxQ'
 * Eq.10 ✓ Q direct update: (1-α)Q + α×1
 * Eq.11 ✓ Q aging: Q = Q_old × ω^t
 *
 * Alg.1 ✓ Aging dulu, lalu update Q untuk dest pesan di buffer
 * Alg.2 ✓ Greedy dari Q / PRoPHET fallback
 * ═══════════════════════════════════════════════════════════════
 */
public class CCRouting extends QLearningRouter {

    // =========================================================================
    // SETTINGS
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
    private static final double GAMMA_P = 0.98; // η decay, Eq.2
    private static final double BETA = 0.25; // β transitivity, Eq.3
    private static final int SEC_IN_TU = 30; // time unit = 30s

    private Map<DTNHost, Double> preds;
    private double lastAgeUpdate = 0.0;

    // =========================================================================
    // LEARNING PARAMETERS
    // =========================================================================
    private double baseDiscountGamma; // γ base untuk Eq.7
    private double learningCoeff; // α dari config (paper: 0.8)

    /**
     * Batas bawah α adaptif.
     * Untuk reproduksi eksak paper: set ALPHA_MIN = learningCoeff
     * (misal 0.8) agar α selalu konstan seperti di paper.
     * Untuk adaptive: gunakan 0.01 agar α bisa turun tiap encounter.
     */
    private static final double ALPHA_MIN = 0.01;

    // =========================================================================
    // FUSION SCORE WEIGHTS (untuk sorting, bukan Q-update)
    // =========================================================================
    private static final double W_RL = 0.8;
    private static final double W_PROPHET = 0.1;
    private static final double W_BUFFER = 0.1;

    // =========================================================================
    // TIMING
    // =========================================================================
    private double updateInterval;
    private double lastUpdateTime = 0.0;

    // =========================================================================
    // DATA STRUCTURES
    // =========================================================================
    private List<Connection> candidateReceiver;
    private Map<DTNHost, Integer> visitCount; // untuk adaptive α

    // =========================================================================
    // CONSTRUCTOR
    // =========================================================================

    public CCRouting(Settings s) {
        super(s);
        Settings cc = new Settings(CCROUTING_NS);

        this.updateInterval = cc.getInt(UPDATE_INTERVAL_S);
        this.totalDest = cc.getInt(TOTAL_STATE_S);
        this.totalAction = cc.getInt(TOTAL_ACTION_S);
        this.baseDiscountGamma = cc.getDouble(BASE_GAMMA_S);
        this.learningCoeff = cc.getDouble(LEARNING_COEFF_S);

        this.learningRate = learningCoeff;
        this.discountFactor = baseDiscountGamma;
        this.agingOmega = GAMMA_P;

        initQTable();
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
        this.candidateReceiver = new ArrayList<>();
        this.visitCount = new LinkedHashMap<>();
    }

    // =========================================================================
    // ENCOUNTER PROBABILITY (Section 3.1)
    // =========================================================================

    /**
     * Eq.2 — Decay P(this,x) seiring waktu.
     * P(a,b) = P_old × η^t
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
     * Eq.1 — Update P(this, other) saat bertemu.
     * P(a,b) = P_old + (1 - P_old) × Pinit
     */
    private void updateEncounterProb(DTNHost other) {
        ageDeliveryPreds();
        double oldVal = preds.getOrDefault(other, 0.0);
        preds.put(other, oldVal + (1.0 - oldVal) * P_INIT);
    }

    /**
     * Eq.3 — Transitivity via node b.
     * P(a,c) = P_old + (1 - P_old) × P(a,b) × P(b,c) × β
     */
    private void updateTransitivity(DTNHost nodeB, CCRouting routerB) {
        ageDeliveryPreds();
        routerB.ageDeliveryPreds();

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

    public double getPredFor(DTNHost host) {
        ageDeliveryPreds();
        return preds.getOrDefault(host, 0.0);
    }

    public Map<Integer, Double> getEncounterProbMap() {
        ageDeliveryPreds();
        Map<Integer, Double> probMap = new HashMap<>();
        for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
            probMap.put(e.getKey().getAddress(), e.getValue());
        }
        return probMap;
    }

    // =========================================================================
    // BUFFER FACTOR (Eq.4)
    // =========================================================================

    /**
     * Eq.4 — BF = 1 - Σ(Bm) / Cinit
     * BF tinggi → buffer lega → node layak relay
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
    // FUSION SCORE (untuk sorting, bukan Q-update)
    // =========================================================================

    /**
     * Fusion = 0.8×Q + 0.1×ΔP + 0.1×BF
     * ΔP = P_other(dest) - P_this(dest)
     *
     * Dipakai HANYA untuk sorting kandidat pesan.
     */
    private double getFusionScore(Message m, DTNHost other) {
        int destAddr = m.getTo().getAddress();
        double rlScore = getQV(destAddr, other.getAddress());

        CCRouting otherRouter = (CCRouting) other.getRouter();
        double prophetDelta = otherRouter.getPredFor(m.getTo())
                - this.getPredFor(m.getTo());
        double bf = getBufferFactor(other);

        return (W_RL * rlScore)
                + (W_PROPHET * Math.max(0.0, prophetDelta))
                + (W_BUFFER * bf);
    }

    // =========================================================================
    // ALGORITHM 1 — Q-UPDATE SAAT ENCOUNTER
    // =========================================================================

    /**
     * Algorithm 1 — Update Q saat bertemu node other.
     *
     * KONSERVATIF: Q-update HANYA untuk destination dari pesan yang
     * ada di buffer node ini (on-policy), sesuai interpretasi literal paper.
     *
     * Urutan sesuai Alg.1:
     * 1. Eq.11 — aging Q dulu
     * 2. Get context info (BF, encounter prob)
     * 3. Eq.9/10 — update Q untuk tiap dest pesan di buffer
     *
     * Adaptive α: α = max(ALPHA_MIN, learningCoeff / visitCount)
     */
    private void updateQTableOnContact(DTNHost other, CCRouting otherRouter) {
        // ── Alg.1 baris 4: Eq.11 aging dulu ──────────────────────────────
        ageQTable(SEC_IN_TU);
        // ─────────────────────────────────────────────────────────────────

        int otherAddr = other.getAddress();

        // Adaptive learning rate
        int visits = visitCount.getOrDefault(other, 0) + 1;
        visitCount.put(other, visits);
        this.learningRate = Math.max(ALPHA_MIN, learningCoeff / visits);

        // Context info
        double bfOther = getBufferFactor(other);
        double dynamicDiscount = baseDiscountGamma * bfOther; // Eq.7

        Map<Integer, Double> otherProbMap = otherRouter.getEncounterProbMap();

        // ── KONSERVATIF: update Q HANYA untuk dest pesan di buffer ──────
        Set<Integer> relevantDests = new HashSet<>();
        for (Message m : getMessageCollection()) {
            relevantDests.add(m.getTo().getAddress());
        }

        for (int destAddr : relevantDests) {
            if (destAddr == getHost().getAddress())
                continue;

            if (otherAddr == destAddr) {
                // Eq.10 — direct delivery
                updateQDirect(destAddr, otherAddr);
            } else {
                // Eq.9 — relay
                double neighborMaxQP = otherRouter.getNeighborMaxQPrime(destAddr, otherProbMap);
                updateQRelay(destAddr, otherAddr, dynamicDiscount, neighborMaxQP);
            }
        }
        // ─────────────────────────────────────────────────────────────────
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

            // Eq.1 — encounter probability
            updateEncounterProb(other);

            // Eq.3 — transitivity
            updateTransitivity(other, otherRouter);

            // Algorithm 1 — Q-update HANYA di sini (tidak di tryOtherMessage)
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

        // Prioritas 1: direct delivery
        if (exchangeDeliverableMessages() != null)
            return;

        // Prioritas 2: relay
        tryOtherMessage();

        // Periodik: aging Q
        double now = SimClock.getTime();
        if ((now - lastUpdateTime) >= updateInterval) {
            lastUpdateTime = now;
            ageQTable(SEC_IN_TU);
        }
    }

    // =========================================================================
    // ALGORITHM 2 — MESSAGE FORWARDING
    // =========================================================================

    /**
     * Algorithm 2 — Pilih relay terbaik.
     *
     * KONSERVATIF: Alg.2 baris 4-5 "Update Q-Table" diinterpretasi
     * sebagai "gunakan Q yang sudah diupdate di changedConnection",
     * bukan "update Q lagi".
     *
     * KASUS A — Q-table PUNYA entry untuk dest:
     * → Greedy: a* = argmax Qd(s,x)
     * → Forward ke other HANYA jika other == a*
     *
     * KASUS B — Q-table BELUM ada entry untuk dest:
     * → PRoPHET ascending gradient (permisif):
     * Forward jika P_other(dest) >= P_this(dest)
     * → >= (bukan >) agar tidak terlalu ketat di sparse network
     *
     * Sorting akhir: fusion score tertinggi
     */
    private void tryOtherMessage() {
        Collection<Message> msgCollection = getMessageCollection();
        if (msgCollection.isEmpty() || candidateReceiver.isEmpty())
            return;

        List<Tuple<Message, Connection>> potentials = new ArrayList<>();

        for (Connection con : candidateReceiver) {
            if (!con.isUp())
                continue; // race condition guard

            DTNHost other = con.getOtherNode(getHost());
            CCRouting otherRouter = (CCRouting) other.getRouter();
            if (otherRouter.isTransferring())
                continue;

            // ── KONSERVATIF: TIDAK ada updateQTableOnContact() di sini ──
            // Gunakan Q yang sudah diupdate di changedConnection
            // ─────────────────────────────────────────────────────────────

            for (Message m : msgCollection) {
                if (otherRouter.hasMessage(m.getId()))
                    continue;
                if (otherRouter.getFreeBufferSize() < m.getSize())
                    continue;

                int destAddr = m.getTo().getAddress();
                boolean shouldForward = false;

                if (hasQEntry(destAddr)) {
                    // ── KASUS A: Greedy dari Q-table ─────────────────────
                    int bestAction = getBestAction(destAddr);
                    if (other.getAddress() == bestAction) {
                        shouldForward = true;
                    }
                    // ──────────────────────────────────────────────────────

                } else {
                    // ── KASUS B: PRoPHET ascending gradient ───────────────
                    // PERMISIF: >= bukan > untuk sparse network
                    double myPred = this.getPredFor(m.getTo());
                    double otherPred = otherRouter.getPredFor(m.getTo());
                    if (otherPred >= myPred) {
                        shouldForward = true;
                    }
                    // ──────────────────────────────────────────────────────
                }

                // Safety: direct delivery
                if (m.getTo() == other) {
                    shouldForward = true;
                }

                if (shouldForward) {
                    potentials.add(new Tuple<>(m, con));
                }
            }
        }

        if (potentials.isEmpty())
            return;

        // Sort fusion score descending
        potentials.sort((t1, t2) -> {
            double fs1 = getFusionScore(
                    t1.getKey(), t1.getValue().getOtherNode(getHost()));
            double fs2 = getFusionScore(
                    t2.getKey(), t2.getValue().getOtherNode(getHost()));
            return Double.compare(fs2, fs1);
        });

        // Kirim satu pesan terbaik per update cycle
        Tuple<Message, Connection> best = potentials.get(0);
        startTransfer(best.getKey(), best.getValue());
    }

    // =========================================================================
    // REPLICATE
    // =========================================================================

    @Override
    public CCRouting replicate() {
        return new CCRouting(this);
    }
}