package routing;

import core.*;
import java.util.*;

/**
 * Context-aware Q-Learning Router dengan Epsilon-Greedy.
 *
 * Implementasi ORQLCI (Opportunistic Routing using Q-Learning with Context
 * Information) dengan enhancement:
 *
 * 1. ε-greedy action selection (better exploration-exploitation balance)
 * 2. Dynamic Q-table (scalable, memory efficient)
 * 3. Greedy fallback via encounter probability saat destination belum di Q-table
 *
 * Sesuai paper:
 * - Section 3.1: Context Information (encounter probability, buffer factor)
 * - Section 3.2: Q-Learning Model
 * - Section 3.3: Learning & Forwarding Design
 */
public class CCRouting extends QLearningRouter {

    // =========================================================================
    // CONFIGURATION
    // =========================================================================

    private static final String CCROUTING_NS = "CCRouting";
    private static final String BASE_GAMMA_S = "baseDiscountGamma";
    private static final String LEARNING_COEFF_S = "learningCoeff";
    private static final String UPDATE_INTERVAL_S = "updateInterval";

    // Epsilon-greedy parameters
    private static final String EPSILON_START_S = "epsilonStart";
    private static final String EPSILON_END_S = "epsilonEnd";
    private static final String EPSILON_DECAY_TYPE_S = "epsilonDecayType";
    private static final String SIMULATION_TIME_S = "simulationTotalTime";

    // Decay parameter keys (opsional, ada default)
    private static final String GAMMA_P_S = "encounterDecayGamma";
    private static final String OMEGA_Q_S = "qAgingOmega";

    // =========================================================================
    // ENCOUNTER PROBABILITY CONSTANTS (sesuai paper Section 3.1)
    // =========================================================================

    private static final double P_INIT = 0.75;  // Initialization constant Pinit
    private static final double BETA = 0.25;    // Transitivity factor β (Eq. 3)
    private static final int SEC_IN_TU = 30;    // Time unit = 30s

    // Decay constants — bisa di-override via config untuk sparse network
    private static final double GAMMA_P_DEFAULT = 0.98; // Decay factor η untuk encounter prob
    private static final double OMEGA_Q_DEFAULT = 0.98; // Aging constant ω untuk Q-value

    // =========================================================================
    // INSTANCE VARIABLES
    // =========================================================================

    // Encounter probability storage
    private Map<DTNHost, Double> preds;
    private double lastAgeUpdate = 0.0;

    // Learning parameters
    private double baseDiscountGamma;
    private double learningCoeff;

    // Epsilon-greedy
    private double epsilonStart;
    private double epsilonEnd;
    private String epsilonDecayType;
    private double currentEpsilon;
    private double simulationTotalTime;

    // Encounter probability decay
    private double gammaP; // η — decay factor untuk encounter prob (Eq. 2)

    // Update control
    private double updateInterval;
    private double lastUpdateTime = 0.0;

    // Current connections
    private List<Connection> candidateReceiver;

    // =========================================================================
    // CONSTRUCTOR
    // =========================================================================

    public CCRouting(Settings s) {
        super(s);
        Settings cc = new Settings(CCROUTING_NS);

        // Learning parameters
        this.baseDiscountGamma = cc.getDouble(BASE_GAMMA_S);
        this.learningCoeff = cc.getDouble(LEARNING_COEFF_S);
        this.updateInterval = cc.getDouble(UPDATE_INTERVAL_S);

        // Set inherited parameters
        this.learningRate = learningCoeff;
        this.discountFactor = baseDiscountGamma;

        // Decay parameters — bisa di-override via config
        this.gammaP = cc.contains(GAMMA_P_S)
                ? cc.getDouble(GAMMA_P_S)
                : GAMMA_P_DEFAULT;
        double omegaQ = cc.contains(OMEGA_Q_S)
                ? cc.getDouble(OMEGA_Q_S)
                : OMEGA_Q_DEFAULT;

        this.agingOmega = omegaQ;
        this.qAgeTimeUnit = SEC_IN_TU;

        // Epsilon-greedy parameters (dengan default values)
        this.epsilonStart = cc.contains(EPSILON_START_S)
                ? cc.getDouble(EPSILON_START_S)
                : 1.0;
        this.epsilonEnd = cc.contains(EPSILON_END_S)
                ? cc.getDouble(EPSILON_END_S)
                : 0.1;
        this.epsilonDecayType = cc.contains(EPSILON_DECAY_TYPE_S)
                ? cc.getSetting(EPSILON_DECAY_TYPE_S)
                : "linear";
        this.simulationTotalTime = cc.contains(SIMULATION_TIME_S)
                ? cc.getDouble(SIMULATION_TIME_S)
                : 43200.0;

        this.currentEpsilon = this.epsilonStart;

        initPreds();
        initLocal();
    }

    protected CCRouting(CCRouting r) {
        super(r);

        this.baseDiscountGamma = r.baseDiscountGamma;
        this.learningCoeff = r.learningCoeff;
        this.updateInterval = r.updateInterval;
        this.gammaP = r.gammaP;

        this.epsilonStart = r.epsilonStart;
        this.epsilonEnd = r.epsilonEnd;
        this.epsilonDecayType = r.epsilonDecayType;
        this.simulationTotalTime = r.simulationTotalTime;
        this.currentEpsilon = r.epsilonStart;

        initPreds();
        initLocal();
    }

    private void initPreds() {
        this.preds = new LinkedHashMap<>();
        this.lastAgeUpdate = 0.0;
    }

    private void initLocal() {
        this.candidateReceiver = new ArrayList<>();
    }

    // =========================================================================
    // EPSILON-GREEDY DECAY
    // =========================================================================

    /**
     * Hitung epsilon saat ini berdasarkan simulation progress.
     * 
     * Linear decay: ε(t) = ε_end + (ε_start - ε_end) × (1 - progress)
     * Exponential decay: ε(t) = ε_end + (ε_start - ε_end) × exp(-3×progress)
     * 
     * @return current epsilon value
     */
    private double calculateCurrentEpsilon() {
        double now = SimClock.getTime();
        double progress = now / simulationTotalTime;
        progress = Math.min(1.0, Math.max(0.0, progress)); // Clamp [0,1]

        if ("exponential".equalsIgnoreCase(epsilonDecayType)) {
            return epsilonEnd + (epsilonStart - epsilonEnd) * Math.exp(-3.0 * progress);
        } else {
            // Linear decay (default)
            return epsilonEnd + (epsilonStart - epsilonEnd) * (1.0 - progress);
        }
    }

    // =========================================================================
    // ENCOUNTER PROBABILITY (sesuai paper Section 3.1)
    // =========================================================================

    /**
     * Age semua encounter probabilities berdasarkan waktu berlalu.
     * 
     * Eq. 2 (paper): P(a,b) = P(a,b)_old × η^t
     * di mana η = GAMMA_P, t = timeDiff / SEC_IN_TU
     */
    private void ageDeliveryPreds() {
        double now = SimClock.getTime();
        double timeDiff = (now - lastAgeUpdate) / SEC_IN_TU;

        if (timeDiff <= 0) {
            return;
        }

        double mult = Math.pow(gammaP, timeDiff);

        for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
            e.setValue(e.getValue() * mult);
        }

        lastAgeUpdate = now;
    }

    /**
     * Update encounter probability saat bertemu node lain.
     * 
     * Eq. 1 (paper): P(a,b) = P(a,b)_old + (1 - P(a,b)_old) × P_init
     * 
     * @param other node yang bertemu
     */
    private void updateEncounterProb(DTNHost other) {
        ageDeliveryPreds();

        double oldVal = preds.getOrDefault(other, 0.0);
        preds.put(other, oldVal + (1.0 - oldVal) * P_INIT);
    }

    /**
     * Update encounter probability via transitivity.
     * 
     * Eq. 3 (paper):
     * P(a,c) = P(a,c)_old + (1 - P(a,c)_old) × P(a,b) × P(b,c) × β
     * 
     * Jika A sering bertemu B dan B sering bertemu C,
     * maka A kemungkinan juga bertemu C.
     * 
     * @param nodeB   node perantara
     * @param routerB router dari nodeB
     */
    private void updateTransitivity(DTNHost nodeB, CCRouting routerB) {
        ageDeliveryPreds();
        routerB.ageDeliveryPreds();

        double pAB = preds.getOrDefault(nodeB, 0.0);
        if (pAB == 0.0) {
            return; // Tidak ada koneksi A-B
        }

        // Iterate encounter probs milik B
        for (Map.Entry<DTNHost, Double> entry : routerB.preds.entrySet()) {
            DTNHost nodeC = entry.getKey();

            if (nodeC.equals(getHost())) {
                continue; // Skip self
            }

            double pBC = entry.getValue();
            double pACold = preds.getOrDefault(nodeC, 0.0);

            // Eq. 3: Transitivity update
            preds.put(nodeC, pACold + (1.0 - pACold) * pAB * pBC * BETA);
        }
    }

    /**
     * Get encounter probability untuk host tertentu.
     * 
     * @param host target host
     * @return probability [0,1]
     */
    public double getPredFor(DTNHost host) {
        ageDeliveryPreds();
        return preds.getOrDefault(host, 0.0);
    }

    /**
     * Get semua encounter probabilities sebagai map <address, prob>.
     * Digunakan untuk Eq. 8 (neighbor max Q').
     * 
     * @return map of node address to encounter probability
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
    // BUFFER FACTOR (sesuai paper Eq. 4)
    // =========================================================================

    /**
     * Hitung buffer factor untuk node tertentu.
     * 
     * Eq. 4 (paper): BF = 1 - (Σ Nm × Bm) / C_init
     * 
     * di mana:
     * - Nm = jumlah message m (dalam ONE simulator: jumlah distinct messages)
     * - Bm = ukuran message m
     * - C_init = max buffer capacity
     * 
     * @param host target host
     * @return buffer factor [0,1], 1 = buffer kosong, 0 = buffer penuh
     */
    private double getBufferFactor(DTNHost host) {
        MessageRouter router = host.getRouter();
        int cTotal = router.getBufferSize();

        // Edge cases
        if (cTotal <= 0 || cTotal == Integer.MAX_VALUE) {
            return 1.0; // Assume unlimited buffer
        }

        long occupied = 0;
        for (Message m : router.getMessageCollection()) {
            occupied += m.getSize();
        }

        // BF = 1 - (occupied / total)
        double bf = 1.0 - (double) occupied / cTotal;

        // Clamp to [0,1]
        return Math.max(0.0, Math.min(1.0, bf));
    }

    // =========================================================================
    // GREEDY FALLBACK VIA ENCOUNTER PROBABILITY
    // =========================================================================

    /**
     * Pilih relay terbaik berdasarkan encounter probability tertinggi ke destination.
     * Digunakan sebagai fallback saat destination belum ada di Q-table.
     *
     * Relay dengan P(relay, dest) tertinggi dipilih — sesuai prinsip greedy
     * yang sama dengan Prophet, tapi hanya sebagai fallback awal sebelum
     * Q-table punya cukup data.
     *
     * @param relay       relay node yang sedang dipertimbangkan
     * @param destination destination node
     * @return encounter probability relay ke destination, atau 0.0 jika tidak diketahui
     */
    private double getEncounterProbToward(DTNHost relay, DTNHost destination) {
        MessageRouter r = relay.getRouter();
        if (!(r instanceof CCRouting)) {
            return 0.0;
        }
        return ((CCRouting) r).getPredFor(destination);
    }

    // =========================================================================
    // Q-TABLE UPDATE (sesuai paper Algorithm 1)
    // =========================================================================

    /**
     * Update Q-table saat bertemu node lain.
     * 
     * Implementasi Algorithm 1 dari paper:
     * 1. Age Q-table sebelum update (Eq. 11)
     * 2. Get context info (buffer factor, encounter probs)
     * 3. Update Q-values untuk SEMUA destinations yang diketahui:
     *    - Destinations dari messages yang sedang dibawa
     *    - Destinations yang sudah ada di Q-table (learned knowledge)
     * 
     * Sesuai paper Section 3.3: node sn update Q_rn(sn,rn) via Eq.10
     * dan Q_dn(sn,rn) via Eq.9 untuk semua dn yang diketahui.
     * 
     * @param other       node yang bertemu
     * @param otherRouter router dari node tersebut
     */
    private void updateQTableOnContact(DTNHost other, CCRouting otherRouter) {
        // Age Q-table milik THIS node dulu (Eq. 11)
        ageQTable(SEC_IN_TU);

        // Age Q-table milik otherRouter juga — wajib sebelum Eq. 8 dibaca
        // agar max_y[Qd(x,y)×P(x,y)] menggunakan nilai yang sudah ter-age
        otherRouter.ageQTable(SEC_IN_TU);

        int otherAddr = other.getAddress();

        // Context information
        double bfOther = getBufferFactor(other);
        double dynamicDiscount = baseDiscountGamma * bfOther; // Eq. 7: γd(s,x) = γ × BFx

        Map<Integer, Double> otherProbMap = otherRouter.getEncounterProbMap();

        // Snapshot destinations SEBELUM loop — mencegah modifikasi qvalues.keySet()
        // saat iterasi (ensureQEntry di dalam updateQRelay bisa tambah key baru)
        Set<Integer> relevantDests = new HashSet<>();
        for (Message m : getMessageCollection()) {
            relevantDests.add(m.getTo().getAddress());
        }
        // Tambahkan destinations dari Q-table (learned knowledge, sesuai Algorithm 1)
        relevantDests.addAll(qvalues.keySet());

        // Update Q untuk setiap destination yang diketahui
        for (int destAddr : relevantDests) {
            if (destAddr == getHost().getAddress()) {
                continue; // Skip self as destination
            }

            if (otherAddr == destAddr) {
                // Case 1: Other IS the destination → Eq. 10
                // Qd(s,x) ← (1-α)×Qd(s,x) + α×Rd(s,x), Rd=1
                updateQDirect(destAddr, otherAddr);
            } else {
                // Case 2: Other is relay → Eq. 9
                // Qd(s,x) ← (1-α)×Qd(s,x) + α×γ×BFx×max_y[Qd(x,y)×P(x,y)]
                double neighborMaxQP = otherRouter.getNeighborMaxQPrime(destAddr, otherProbMap);
                updateQRelay(destAddr, otherAddr, dynamicDiscount, neighborMaxQP);
            }
        }
    }

    // =========================================================================
    // CONNECTION EVENT HANDLER
    // =========================================================================

    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);

        DTNHost other = con.getOtherNode(getHost());

        // Pastikan router adalah CCRouting
        if (!(other.getRouter() instanceof CCRouting)) {
            return;
        }

        CCRouting otherRouter = (CCRouting) other.getRouter();

        if (con.isUp()) {
            // Connection UP

            // Add ke candidate receivers
            if (!candidateReceiver.contains(con)) {
                candidateReceiver.add(con);
            }

            // Update encounter probability (Eq. 1)
            updateEncounterProb(other);

            // Update transitivity (Eq. 3)
            updateTransitivity(other, otherRouter);

            // Update Q-table (Algorithm 1)
            updateQTableOnContact(other, otherRouter);

        } else {
            // Connection DOWN
            candidateReceiver.remove(con);
        }
    }

    // =========================================================================
    // MESSAGE FORWARDING (sesuai paper Algorithm 2)
    // =========================================================================

    @Override
    public void update() {
        super.update();

        if (isTransferring() || !canStartTransfer()) {
            return;
        }

        // Update epsilon berdasarkan simulation progress
        this.currentEpsilon = calculateCurrentEpsilon();

        // Prioritas 1: Forward messages ke destination langsung
        if (exchangeDeliverableMessages() != null) {
            return;
        }

        // Prioritas 2: Forward via relay nodes
        tryOtherMessage();

        // Periodic Q-table aging
        double now = SimClock.getTime();
        if ((now - lastUpdateTime) >= updateInterval) {
            lastUpdateTime = now;
            ageQTable(SEC_IN_TU);
        }
    }

    /**
     * Coba forward messages via relay nodes.
     *
     * Implementasi Algorithm 2 dengan ε-greedy action selection:
     *
     * CASE 1: Other IS destination → langsung forward (prioritas tertinggi)
     *
     * CASE 2: Destination ada di Q-table → ε-greedy
     * - Keputusan explore/exploit dibuat SEKALI per connection
     * - Exploration (prob ε): forward ke relay ini, score acak [0,1]
     * - Exploitation (prob 1-ε): forward hanya jika relay ini adalah argmax Q
     *
     * CASE 3: Destination BELUM ada di Q-table → greedy fallback
     * - Pilih relay dengan encounter probability tertinggi ke destination
     * - Sesuai arahan dosen: ganti directional prediction dengan greedy
     */
    private void tryOtherMessage() {
        Collection<Message> msgCollection = getMessageCollection();

        if (msgCollection.isEmpty() || candidateReceiver.isEmpty()) {
            return;
        }

        Tuple<Message, Connection> bestCandidate = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (Connection con : candidateReceiver) {
            if (!con.isUp()) {
                continue;
            }

            DTNHost other = con.getOtherNode(getHost());

            if (!(other.getRouter() instanceof CCRouting)) {
                continue;
            }

            CCRouting otherRouter = (CCRouting) other.getRouter();

            if (otherRouter.isTransferring()) {
                continue;
            }

            // Keputusan explore/exploit dibuat SEKALI per connection,
            // berlaku untuk semua messages ke connection ini.
            boolean explore = (Math.random() < this.currentEpsilon);

            for (Message m : msgCollection) {
                if (otherRouter.hasMessage(m.getId())) {
                    continue;
                }
                if (otherRouter.getFreeBufferSize() < m.getSize()) {
                    continue;
                }

                int destAddr = m.getTo().getAddress();
                boolean shouldForward = false;
                double candidateScore = Double.NEGATIVE_INFINITY;

                // CASE 1: Other IS destination → always forward
                if (m.getTo() == other) {
                    shouldForward = true;
                    candidateScore = Double.POSITIVE_INFINITY;
                }
                // CASE 2: Destination ada di Q-table → ε-greedy
                else if (hasQEntry(destAddr)) {
                    if (explore) {
                        // EXPLORATION: score acak [0,1], bersaing fair
                        shouldForward = true;
                        candidateScore = Math.random();
                    } else {
                        // EXPLOITATION: argmax Q
                        int bestAction = getBestAction(destAddr);
                        if (other.getAddress() == bestAction) {
                            shouldForward = true;
                            candidateScore = getQV(destAddr, bestAction);
                        }
                    }
                }
                // CASE 3: Destination belum di Q-table → greedy via encounter prob
                else {
                    double encProb = getEncounterProbToward(other, m.getTo());
                    if (encProb > 0.0) {
                        shouldForward = true;
                        candidateScore = encProb;
                    }
                }

                if (shouldForward && candidateScore > bestScore) {
                    bestScore = candidateScore;
                    bestCandidate = new Tuple<>(m, con);
                }
            }
        }

        if (bestCandidate != null) {
            startTransfer(bestCandidate.getKey(), bestCandidate.getValue());
        }
    }

    // =========================================================================
    // ROUTER REPLICATION
    // =========================================================================

    @Override
    public CCRouting replicate() {
        return new CCRouting(this);
    }

    // =========================================================================
    // GETTERS (untuk debugging/reporting)
    // =========================================================================

    public double getCurrentEpsilon() {
        return currentEpsilon;
    }

    public int getEncounterProbCount() {
        return preds.size();
    }

    @Override
    public String toString() {
        return super.toString() + String.format(" [ε=%.3f, preds=%d, %s]",
                currentEpsilon, preds.size(), getQTableStats());
    }
}