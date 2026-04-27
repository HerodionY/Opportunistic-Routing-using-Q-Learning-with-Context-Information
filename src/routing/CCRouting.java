package routing;

import core.*;
import java.util.*;

public class CCRouting extends QLearningRouter {
    private static final String CCROUTING_NS = "CCRouting";
    private static final String BASE_GAMMA_S = "baseDiscountGamma";
    private static final String LEARNING_COEFF_S = "learningCoeff";
    private static final String UPDATE_INTERVAL_S = "updateInterval";
    private static final String EPSILON_START_S = "epsilonStart";
    private static final String EPSILON_END_S = "epsilonEnd";
    private static final String EPSILON_DECAY_TYPE_S = "epsilonDecayType";
    private static final String SIMULATION_TIME_S = "simulationTotalTime";
    private static final String GAMMA_P_S = "encounterDecayGamma";
    private static final String OMEGA_Q_S = "qAgingOmega";
    private static final String INITIAL_ENERGY_S = "initialEnergy";
    private static final String SCAN_ENERGY_S = "scanEnergy";
    private static final String TRANSMIT_ENERGY_S = "transmitEnergy";
    private static final String RECEIVE_ENERGY_S = "receiveEnergy";
    private static final String HARD_THRESHOLD_S = "hardEnergyThreshold";
    private static final String SOFT_THRESHOLD_S = "softEnergyThreshold";
    private static final String SOFT_UPPER_S = "softEnergyUpperBound";
    private static final double P_INIT = 0.75; // Initialization constant
    private static final double BETA = 0.25; // Transitivity factor β
    private static final int SEC_IN_TU = 30; // Time unit = 30s
    private static final double GAMMA_P_DEFAULT = 0.98; // η untuk encounter prob
    private static final double OMEGA_Q_DEFAULT = 0.98; // ω untuk Q-value aging
    private static final double INITIAL_ENERGY_DEFAULT = 1000.0;
    private static final double SCAN_ENERGY_DEFAULT = 0.1;
    private static final double TRANSMIT_ENERGY_DEFAULT = 0.5; // per second
    private static final double RECEIVE_ENERGY_DEFAULT = 0.1; // per second
    private static final double HARD_THRESHOLD_DEFAULT = 0.04; // 4%
    private static final double SOFT_THRESHOLD_DEFAULT = 0.05; // 5%
    private static final double SOFT_UPPER_DEFAULT = 0.20; // 20%
    private static final double DIRECTION_THRESHOLD_DEGREES = 45.0;
    private static final double DIRECTION_THRESHOLD_COS = Math.cos(Math.toRadians(DIRECTION_THRESHOLD_DEGREES));
    private Map<DTNHost, Double> preds;
    private double lastAgeUpdate = 0.0;

    private double baseDiscountGamma;
    private double learningCoeff;
    private double gammaP; // η — decay factor untuk encounter prob

    private double epsilonStart;
    private double epsilonEnd;
    private String epsilonDecayType;
    private double currentEpsilon;
    private double simulationTotalTime;

    private double updateInterval;
    private double lastUpdateTime = 0.0;

    private List<Connection> candidateReceiver;

    private double maxEnergy;
    private double currentEnergy;
    private double scanEnergy; // per scan
    private double transmitEnergy; // per second per connection
    private double receiveEnergy; // per second per connection
    private double hardEnergyThreshold; // default 4%
    private double softEnergyThreshold; // default 5%
    private double softEnergyUpper; // default 20%
    private double initialEnergyConfig;
    private double lastScanEnergyUpdate = 0.0;
    private double lastEnergyUpdate = 0.0;
    private double cachedScanInterval = 120.0;
    private Random rng = new Random(42L); // Fallback seed; di-overwrite di init()

    public CCRouting(Settings s) {
        super(s);
        Settings cc = new Settings(CCROUTING_NS);

        this.baseDiscountGamma = cc.getDouble(BASE_GAMMA_S);
        this.learningCoeff = cc.getDouble(LEARNING_COEFF_S);
        this.updateInterval = cc.getDouble(UPDATE_INTERVAL_S);
        this.learningRate = learningCoeff;
        this.discountFactor = baseDiscountGamma;
        this.gammaP = cc.contains(GAMMA_P_S)
                ? cc.getDouble(GAMMA_P_S)
                : GAMMA_P_DEFAULT;

        double omegaQ = cc.contains(OMEGA_Q_S)
                ? cc.getDouble(OMEGA_Q_S)
                : OMEGA_Q_DEFAULT;

        this.agingOmega = omegaQ;
        this.qAgeTimeUnit = SEC_IN_TU;
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

        this.initialEnergyConfig = cc.contains(INITIAL_ENERGY_S)
                ? cc.getDouble(INITIAL_ENERGY_S)
                : INITIAL_ENERGY_DEFAULT;
        this.scanEnergy = cc.contains(SCAN_ENERGY_S)
                ? cc.getDouble(SCAN_ENERGY_S)
                : SCAN_ENERGY_DEFAULT;
        this.transmitEnergy = cc.contains(TRANSMIT_ENERGY_S)
                ? cc.getDouble(TRANSMIT_ENERGY_S)
                : TRANSMIT_ENERGY_DEFAULT;
        this.receiveEnergy = cc.contains(RECEIVE_ENERGY_S)
                ? cc.getDouble(RECEIVE_ENERGY_S)
                : RECEIVE_ENERGY_DEFAULT;
        this.hardEnergyThreshold = cc.contains(HARD_THRESHOLD_S)
                ? cc.getDouble(HARD_THRESHOLD_S)
                : HARD_THRESHOLD_DEFAULT;
        this.softEnergyThreshold = cc.contains(SOFT_THRESHOLD_S)
                ? cc.getDouble(SOFT_THRESHOLD_S)
                : SOFT_THRESHOLD_DEFAULT;
        this.softEnergyUpper = cc.contains(SOFT_UPPER_S)
                ? cc.getDouble(SOFT_UPPER_S)
                : SOFT_UPPER_DEFAULT;

        this.maxEnergy = initialEnergyConfig;
        this.currentEnergy = initialEnergyConfig;

        this.cachedScanInterval = readScanInterval();

        initPreds();
        initLocal();
    }

    protected CCRouting(CCRouting r) {
        super(r);

        // Copy learning parameters
        this.baseDiscountGamma = r.baseDiscountGamma;
        this.learningCoeff = r.learningCoeff;
        this.updateInterval = r.updateInterval;
        this.gammaP = r.gammaP;

        // Copy epsilon parameters
        this.epsilonStart = r.epsilonStart;
        this.epsilonEnd = r.epsilonEnd;
        this.epsilonDecayType = r.epsilonDecayType;
        this.simulationTotalTime = r.simulationTotalTime;
        this.currentEpsilon = r.epsilonStart;

        // Copy energy parameters
        this.initialEnergyConfig = r.initialEnergyConfig;
        this.scanEnergy = r.scanEnergy;
        this.transmitEnergy = r.transmitEnergy;
        this.receiveEnergy = r.receiveEnergy;
        this.hardEnergyThreshold = r.hardEnergyThreshold;
        this.softEnergyThreshold = r.softEnergyThreshold;
        this.softEnergyUpper = r.softEnergyUpper;
        this.cachedScanInterval = r.cachedScanInterval;

        // Energy initialization DITUNDA sampai init() dipanggil
        // (karena host belum tersedia di copy constructor)
        this.maxEnergy = -1.0; // Sentinel value
        this.currentEnergy = -1.0;

        this.lastScanEnergyUpdate = 0.0;
        this.lastEnergyUpdate = 0.0;

        initPreds();
        initLocal();
    }

    private double readScanInterval() {
        try {
            Settings iface = new Settings("btInterface");
            if (iface.contains("scanInterval")) {
                return iface.getDouble("scanInterval");
            }
        } catch (Exception e) {
            // Ignore, use default
        }
        return 120.0;
    }

    private void initPreds() {
        this.preds = new LinkedHashMap<>();
        this.lastAgeUpdate = 0.0;
    }

    private void initLocal() {
        this.candidateReceiver = new ArrayList<>();
    }

    /**
     * OVERRIDE init() untuk inisialisasi energy SETELAH host tersedia.
     * 
     * Energy capacity: random [initialEnergy-200, initialEnergy]
     * Seed: deterministik berdasarkan address node untuk reproducibility.
     */
    @Override
    public void init(DTNHost host, List<MessageListener> mListeners) {
        super.init(host, mListeners);

        // Inisialisasi energy jika belum di-set
        if (maxEnergy < 0) {
            double minEnergy = Math.max(0, initialEnergyConfig - 200.0);

            // Seed dari address untuk deterministic per-node
            Random nodeRng = new Random(host.getAddress() + 12345L);

            this.maxEnergy = minEnergy + nodeRng.nextDouble()
                    * (initialEnergyConfig - minEnergy);
            this.currentEnergy = this.maxEnergy;
        }

        // Inisialisasi RNG deterministik berbasis address node.
        // Setiap node mendapat seed unik → exploration reproducible per-node.
        this.rng = new Random(host.getAddress() + 99999L);
    }

    // =========================================================================
    // ENERGY AWARENESS
    // =========================================================================

    /**
     * Hitung Energy Factor (EF) untuk node ini.
     *
     * EF digunakan sebagai pengganda pada discount factor:
     * γd(s,x) = γ × BFx × EFx (Extended Eq. 7)
     *
     * Zona:
     * - ratio > softEnergyUpper (default 20%) → EF = 1.0 (no penalty)
     * - softEnergyThreshold < ratio ≤ softEnergyUpper → EF linear [0.0, 1.0]
     * - ratio ≤ softEnergyThreshold (default 5%) → EF = 0.0 (max penalty)
     *
     * @return EF ∈ [0.0, 1.0]
     */
    public double getEnergyFactor() {
        if (maxEnergy <= 0) {
            throw new IllegalStateException("maxEnergy must be > 0");
        }

        double ratio = currentEnergy / maxEnergy;

        if (ratio > softEnergyUpper) {
            return 1.0;
        } else if (ratio <= softEnergyThreshold) {
            return 0.0;
        } else {
            // Linear interpolation dalam soft zone
            return (ratio - softEnergyThreshold)
                    / (softEnergyUpper - softEnergyThreshold);
        }
    }

    /**
     * Cek apakah node dalam hard gate (energy ≤ hardEnergyThreshold).
     * Node dalam hard gate menolak receive pesan baru.
     *
     * @return true jika dalam hard gate zone
     */
    public boolean isInHardGate() {
        if (maxEnergy <= 0)
            return false;
        return (currentEnergy / maxEnergy) <= hardEnergyThreshold;
    }

    /**
     * Get Energy Factor dari node lain (untuk discount factor calculation).
     *
     * @param host target host
     * @return EF dari host tersebut, atau 1.0 jika bukan CCRouting
     */
    private double getEnergyFactorOf(DTNHost host) {
        MessageRouter r = host.getRouter();
        if (!(r instanceof CCRouting)) {
            return 1.0;
        }
        CCRouting ccRouter = (CCRouting) r;
        // Guard: jika router target belum melewati init() (maxEnergy masih sentinel),
        // kembalikan nilai netral 1.0 agar tidak melempar IllegalStateException.
        return ccRouter.getMaxEnergy() > 0 ? ccRouter.getEnergyFactor() : 1.0;
    }

    /**
     * Kurangi energy untuk scanning activity.
     * Dipanggil tiap update(), tapi hanya consume setiap scanInterval.
     */
    private void consumeScanEnergy() {
        // Guard: cachedScanInterval = 0 akan menyebabkan infinite loop.
        // Bisa terjadi jika key 'scanInterval' tidak ada di config interface.
        if (cachedScanInterval <= 0) {
            return;
        }

        double now = SimClock.getTime();

        // Hitung berapa scan periods yang sudah lewat
        while (now >= lastScanEnergyUpdate + cachedScanInterval) {
            currentEnergy = Math.max(0.0, currentEnergy - scanEnergy);
            lastScanEnergyUpdate += cachedScanInterval; // INCREMENT!
        }
    }

    /**
     * Kurangi energy untuk transmitting/receiving.
     * 
     * Model: Energy proportional dengan connections aktif.
     * - Transmit: transmitEnergy × timeDiff × numTransmitting
     * - Receive: receiveEnergy × timeDiff × numReceiving
     * 
     * FIXED: Gunakan actual connection state, bukan sendingConnections.
     */
    private void consumeTransferEnergy() {
        double now = SimClock.getTime();
        double timeDiff = now - lastEnergyUpdate;

        if (timeDiff <= 0) {
            lastEnergyUpdate = now;
            return;
        }

        // Count active transmit/receive connections
        int numTransmitting = 0;
        int numReceiving = 0;

        for (Connection con : getConnections()) {
            if (!con.isUp()) {
                continue;
            }

            Message transferringMsg = con.getMessage();
            if (transferringMsg == null) {
                continue;
            }

            // Check apakah THIS node adalah sender atau receiver
            if (con.isInitiator(getHost())) {
                numTransmitting++;
            } else {
                numReceiving++;
            }
        }

        // Consume energy proporsional dengan connections & time
        if (numTransmitting > 0) {
            double energyUsed = transmitEnergy * timeDiff * numTransmitting;
            currentEnergy = Math.max(0.0, currentEnergy - energyUsed);
        }

        if (numReceiving > 0) {
            double energyUsed = receiveEnergy * timeDiff * numReceiving;
            currentEnergy = Math.max(0.0, currentEnergy - energyUsed);
        }

        lastEnergyUpdate = now;
    }

    /**
     * OVERRIDE checkReceiving untuk implement hard gate.
     * 
     * Node dalam hard gate (energy ≤ 4%) menolak receive pesan baru.
     * Node masih bisa forward pesan yang sudah ada di buffer.
     */
    @Override
    protected int checkReceiving(Message m) {
        if (isInHardGate()) {
            return DENIED_UNSPECIFIED; // Reject karena low energy
        }
        return super.checkReceiving(m);
    }

    // =========================================================================
    // EPSILON-GREEDY DECAY
    // =========================================================================

    /**
     * Hitung epsilon saat ini berdasarkan simulation progress.
     * 
     * Linear: ε(t) = ε_end + (ε_start - ε_end) × (1 - progress)
     * Exponential: ε(t) = ε_end + (ε_start - ε_end) × exp(-3×progress)
     * 
     * @return current epsilon ∈ [ε_end, ε_start]
     */
    private double calculateCurrentEpsilon() {
        double now = SimClock.getTime();
        double progress = now / simulationTotalTime;
        progress = Math.min(1.0, Math.max(0.0, progress));

        if ("exponential".equalsIgnoreCase(epsilonDecayType)) {
            return epsilonEnd + (epsilonStart - epsilonEnd)
                    * Math.exp(-3.0 * progress);
        } else {
            // Linear decay (default)
            return epsilonEnd + (epsilonStart - epsilonEnd) * (1.0 - progress);
        }
    }

    // =========================================================================
    // ENCOUNTER PROBABILITY (sesuai paper Section 3.1)
    // =========================================================================

    /**
     * Age semua encounter probabilities.
     * 
     * Eq. 2 (paper): P(a,b) = P(a,b)_old × η^t
     * di mana η = gammaP, t = timeDiff / SEC_IN_TU
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
     * Update encounter probability saat bertemu node.
     * 
     * Eq. 1 (paper): P(a,b) = P(a,b)_old + (1 - P(a,b)_old) × P_init
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
     */
    private void updateTransitivity(DTNHost nodeB, CCRouting routerB) {
        ageDeliveryPreds();
        routerB.ageDeliveryPreds();

        double pAB = preds.getOrDefault(nodeB, 0.0);
        if (pAB == 0.0) {
            return;
        }

        for (Map.Entry<DTNHost, Double> entry : routerB.preds.entrySet()) {
            DTNHost nodeC = entry.getKey();

            if (nodeC.equals(getHost())) {
                continue;
            }

            double pBC = entry.getValue();
            double pACold = preds.getOrDefault(nodeC, 0.0);

            preds.put(nodeC, pACold + (1.0 - pACold) * pAB * pBC * BETA);
        }
    }

    /**
     * Get encounter probability untuk host tertentu.
     */
    public double getPredFor(DTNHost host) {
        ageDeliveryPreds();
        return preds.getOrDefault(host, 0.0);
    }

    /**
     * Get encounter probabilities sebagai map <address, prob>.
     * Digunakan untuk Eq. 8 (neighbor max Q').
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
     * Hitung buffer factor untuk node.
     * 
     * Eq. 4 (paper): BF = 1 - (Σ Nm × Bm) / C_init
     * 
     * @return BF ∈ [0, 1], 1 = empty, 0 = full
     */
    private double getBufferFactor(DTNHost host) {
        MessageRouter router = host.getRouter();
        int cTotal = router.getBufferSize();

        if (cTotal <= 0 || cTotal == Integer.MAX_VALUE) {
            return 1.0;
        }

        long occupied = 0;
        for (Message m : router.getMessageCollection()) {
            occupied += m.getSize();
        }

        double bf = 1.0 - (double) occupied / cTotal;
        return Math.max(0.0, Math.min(1.0, bf));
    }

    // =========================================================================
    // DIRECTIONAL PREDICTION (sesuai paper Algorithm 2)
    // =========================================================================

    /**
     * Hitung cosine similarity antara arah gerak relay dan arah ke destination.
     * 
     * Digunakan saat destination TIDAK ada di Q-table (fallback).
     * Sesuai paper Algorithm 2.
     * 
     * cos(θ) = (V_move · V_target) / (||V_move|| × ||V_target||)
     * 
     * @return cosine ∈ [-1, 1], atau -1 jika data tidak lengkap
     */
    private double getDirectionalCosine(DTNHost relay, DTNHost destination) {
        Coord relayLocation = relay.getLocation();
        Coord relayWaypoint = relay.getDestination();
        Coord destinationLocation = destination.getLocation();

        if (relayLocation == null || relayWaypoint == null
                || destinationLocation == null) {
            return -1.0;
        }

        // Vector arah gerak relay
        double moveX = relayWaypoint.getX() - relayLocation.getX();
        double moveY = relayWaypoint.getY() - relayLocation.getY();

        // Vector arah ke destination
        double targetX = destinationLocation.getX() - relayLocation.getX();
        double targetY = destinationLocation.getY() - relayLocation.getY();

        // Magnitudes
        double moveNorm = Math.hypot(moveX, moveY);
        if (moveNorm == 0.0) {
            return -1.0; // Relay tidak bergerak
        }

        double targetNorm = Math.hypot(targetX, targetY);
        if (targetNorm == 0.0) {
            return 1.0; // Relay sudah di lokasi destination
        }

        // Cosine similarity
        return ((moveX * targetX) + (moveY * targetY))
                / (moveNorm * targetNorm);
    }

    // =========================================================================
    // Q-TABLE UPDATE (sesuai paper Algorithm 1)
    // =========================================================================

    /**
     * Update Q-table saat bertemu node lain.
     * 
     * Implementasi Algorithm 1 dari paper dengan energy awareness.
     * 
     * Extended Eq. 7:
     * γd(s,x) = γ × BFx × EFx
     * 
     * EFx memberikan gradual penalty untuk relay dengan low energy.
     */
    private void updateQTableOnContact(DTNHost other, CCRouting otherRouter) {
        // Age Q-table milik node ini saja.
        // Sesuai paper Algorithm 1: setiap node hanya mengupdate state-nya sendiri.
        // otherRouter akan meng-age Q-tablenya sendiri di update() miliknya.
        ageQTable(SEC_IN_TU);

        int otherAddr = other.getAddress();

        // Context information
        double bfOther = getBufferFactor(other);
        double efOther = getEnergyFactorOf(other);

        // Extended Eq. 7: γd(s,x) = γ × BFx × EFx
        double dynamicDiscount = baseDiscountGamma * bfOther * efOther;

        Map<Integer, Double> otherProbMap = otherRouter.getEncounterProbMap();

        // Collect relevant destinations
        Set<Integer> relevantDests = new HashSet<>();
        for (Message m : getMessageCollection()) {
            relevantDests.add(m.getTo().getAddress());
        }
        // Juga update untuk destinations yang sudah ada di Q-table
        relevantDests.addAll(qvalues.keySet());

        // Update Q untuk setiap destination
        for (int destAddr : relevantDests) {
            if (destAddr == getHost().getAddress()) {
                continue;
            }

            if (otherAddr == destAddr) {
                // Case 1: Other IS destination → Eq. 10
                updateQDirect(destAddr, otherAddr);
            } else {
                // Case 2: Other is relay → Eq. 9
                double neighborMaxQP = otherRouter.getNeighborMaxQPrime(
                        destAddr, otherProbMap);
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

        if (!(other.getRouter() instanceof CCRouting)) {
            return;
        }

        CCRouting otherRouter = (CCRouting) other.getRouter();

        if (con.isUp()) {
            // Connection UP

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

        // Consume energy
        consumeScanEnergy();
        consumeTransferEnergy();

        if (isTransferring() || !canStartTransfer()) {
            return;
        }

        // Update epsilon
        this.currentEpsilon = calculateCurrentEpsilon();

        // Priority 1: Direct delivery
        if (exchangeDeliverableMessages() != null) {
            return;
        }

        // Priority 2: Relay forwarding
        tryOtherMessage();

        // Periodic Q-table aging
        double now = SimClock.getTime();
        if ((now - lastUpdateTime) >= updateInterval) {
            lastUpdateTime = now;
            ageQTable(SEC_IN_TU);
        }
    }

    /**
     * Forward messages via relay nodes.
     * 
     * Implementasi Algorithm 2 dengan ε-greedy dan energy awareness:
     * 
     * CASE 0: Skip relay dalam hard gate (akan reject receive)
     * 
     * CASE 1: Other IS destination → always forward (highest priority)
     * 
     * CASE 2: Destination ada di Q-table → ε-greedy
     * - Exploration (prob ε): try forward, random score
     * - Exploitation (prob 1-ε): forward only if other = argmax Q
     * 
     * CASE 3: Destination TIDAK di Q-table → directional prediction
     * (sesuai paper Algorithm 2, bukan encounter probability!)
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

            // CASE 0: Skip relay dalam hard gate
            if (otherRouter.isInHardGate()) {
                continue;
            }

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

                // CASE 1: Direct delivery
                if (m.getTo() == other) {
                    shouldForward = true;
                    candidateScore = Double.POSITIVE_INFINITY;
                }
                // CASE 2: Q-table exists → ε-greedy
                else if (hasQEntry(destAddr)) {
                    // Exploration decision PER MESSAGE (FIXED!)
                    // Gunakan rng deterministik (di-seed dari host address) agar
                    // hasil simulasi reproducible antar run dengan seed yang sama.
                    boolean explore = (rng.nextDouble() < this.currentEpsilon);

                    if (explore) {
                        // Exploration
                        shouldForward = true;
                        candidateScore = 1000.0 + rng.nextDouble();
                    } else {
                        // Exploitation
                        int bestAction = getBestAction(destAddr);
                        if (other.getAddress() == bestAction) {
                            shouldForward = true;
                            candidateScore = getQV(destAddr, bestAction);
                        }
                    }
                }
                // CASE 3: No Q-entry → directional prediction (sesuai paper!)
                else {
                    double directionalCosine = getDirectionalCosine(
                            other, m.getTo());

                    if (directionalCosine >= DIRECTION_THRESHOLD_COS) {
                        shouldForward = true;
                        candidateScore = directionalCosine;
                    }
                }

                // Track best candidate
                if (shouldForward && candidateScore > bestScore) {
                    bestScore = candidateScore;
                    bestCandidate = new Tuple<>(m, con);
                }
            }
        }

        // Forward best message
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

    public double getCurrentEnergy() {
        return currentEnergy;
    }

    public double getMaxEnergy() {
        return maxEnergy;
    }

    public double getEnergyRatio() {
        return maxEnergy > 0 ? currentEnergy / maxEnergy : 1.0;
    }

    @Override
    public String toString() {
        return super.toString() + String.format(
                " [ε=%.3f, E=%.0f/%.0f(%.0f%%), EF=%.2f, %s]",
                currentEpsilon,
                currentEnergy, maxEnergy, getEnergyRatio() * 100,
                maxEnergy > 0 ? getEnergyFactor() : 1.0,
                getQTableStats());
    }
}