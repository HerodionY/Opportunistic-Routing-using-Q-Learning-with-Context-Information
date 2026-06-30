package routing;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.MessageRouter;
import core.Settings;
import core.SimClock;
import core.Tuple;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ORQLCI router dengan energy-awareness menggunakan EnergyAwareRouter dari THE ONE.
 *
 * Logic identik dengan CCRoutingExpert, perbedaan HANYA pada implementasi energi:
 *
 *   CCRoutingExpert:             ORQLCIWithEnergyAwareness:
 *   - Manual currentEnergy       - Delegasi ke EnergyAwareRouter (comBus)
 *   - Manual consumeScan/Transfer- reduceSendingAndScanningEnergy() dari THE ONE
 *   - Seeded Random per node     - setEnergy(range[]) dari EnergyAwareRouter
 *   - Radio tidak dimatikan      - Radio dimatikan otomatis saat energi = 0
 *   - receiveEnergy terpisah     - Tidak ada (EnergyAwareRouter hanya scan+transmit)
 *
 * Hierarki:
 *   EnergyAwareRouter (THE ONE)
 *     -> QLearningRouterEnergyAware   (Q-table + EF tracking)
 *       -> ORQLCIWithEnergyAwareness  (routing logic + PRoPHET + epsilon-greedy)
 *
 * Setting yang DIPERLUKAN di config:
 *
 *   # Group settings (dibaca EnergyAwareRouter):
 *   Group.intialEnergy = <nilai atau range, misal: 50,100>
 *   Group.scanEnergy   = <joule per scan>
 *   Group.transmitEnergy = <joule per detik transmit>
 *
 *   # ORQLCIWithEnergyAwareness settings:
 *   ORQLCIWithEnergyAwareness.baseDiscountGamma = 0.6
 *   ORQLCIWithEnergyAwareness.learningCoeff     = 0.8
 *   ORQLCIWithEnergyAwareness.updateInterval    = 900
 *   ORQLCIWithEnergyAwareness.epsilonStart      = 1.0
 *   ORQLCIWithEnergyAwareness.epsilonEnd        = 0.1
 *   ORQLCIWithEnergyAwareness.epsilonDecayType  = linear  # atau exponential
 *   ORQLCIWithEnergyAwareness.simulationTotalTime = 43200
 *   ORQLCIWithEnergyAwareness.encounterDecayGamma = 0.98
 *   ORQLCIWithEnergyAwareness.qAgingOmega        = 0.98
 */
public class ORQLCIWithEnergyAwareness extends QLearningRouterEnergyAware {

    // ---- Settings namespace ----
    private static final String NS = "ORQLCIWithEnergyAwareness";

    private static final String BASE_GAMMA_S         = "baseDiscountGamma";
    private static final String LEARNING_COEFF_S     = "learningCoeff";
    private static final String UPDATE_INTERVAL_S    = "updateInterval";
    private static final String EPSILON_START_S      = "epsilonStart";
    private static final String EPSILON_END_S        = "epsilonEnd";
    private static final String EPSILON_DECAY_TYPE_S = "epsilonDecayType";
    private static final String SIMULATION_TIME_S    = "simulationTotalTime";
    private static final String GAMMA_P_S            = "encounterDecayGamma";
    private static final String OMEGA_Q_S            = "qAgingOmega";

    // ---- PRoPHET constants ----
    private static final double P_INIT      = 0.75;  // delivery predictability init
    private static final double BETA        = 0.25;  // transitivity factor
    private static final int    SEC_IN_TU   = 30;    // detik per time-unit untuk PRoPHET aging
    private static final double GAMMA_P_DEF = 0.98;  // default PRoPHET aging decay
    private static final double OMEGA_Q_DEF = 0.98;  // default Q aging omega

    /**
     * EF threshold: node dengan EF di bawah ini tidak meneruskan pesan orang lain,
     * tapi tetap menerima pesan yang ditujukan ke dirinya sendiri.
     */
    private static final double EF_THRESHOLD = 0.10;

    // ---- Routing parameters ----
    private double baseDiscountGamma;
    private double gammaP;          // PRoPHET delivery predictability aging decay
    private double updateInterval;
    private double lastUpdateTime = 0.0;

    // ---- Epsilon-greedy ----
    private double epsilonStart;
    private double epsilonEnd;
    private String epsilonDecayType;
    private double currentEpsilon;
    private double simulationTotalTime;

    // ---- PRoPHET encounter probability ----
    private Map<DTNHost, Double> preds;
    private double               lastAgeUpdate = 0.0;

    // ---- Kandidat koneksi untuk forwarding ----
    private List<Connection> candidateReceiver;

    // =========================================================================
    // CONSTRUCTORS
    // =========================================================================

    public ORQLCIWithEnergyAwareness(Settings s) {
        super(s);
        Settings cc = new Settings(NS);

        this.baseDiscountGamma = cc.getDouble(BASE_GAMMA_S);
        this.learningRate      = cc.getDouble(LEARNING_COEFF_S);  // alpha
        this.discountFactor    = baseDiscountGamma;                 // gamma base
        this.updateInterval    = cc.getDouble(UPDATE_INTERVAL_S);

        this.gammaP    = cc.contains(GAMMA_P_S) ? cc.getDouble(GAMMA_P_S) : GAMMA_P_DEF;
        double omegaQ  = cc.contains(OMEGA_Q_S) ? cc.getDouble(OMEGA_Q_S) : OMEGA_Q_DEF;

        this.agingOmega  = omegaQ;
        this.qAgeTimeUnit = SEC_IN_TU;

        this.epsilonStart       = cc.contains(EPSILON_START_S)      ? cc.getDouble(EPSILON_START_S)    : 1.0;
        this.epsilonEnd         = cc.contains(EPSILON_END_S)        ? cc.getDouble(EPSILON_END_S)      : 0.1;
        this.epsilonDecayType   = cc.contains(EPSILON_DECAY_TYPE_S) ? cc.getSetting(EPSILON_DECAY_TYPE_S) : "linear";
        this.simulationTotalTime = cc.contains(SIMULATION_TIME_S)   ? cc.getDouble(SIMULATION_TIME_S)  : 43200.0;
        this.currentEpsilon     = this.epsilonStart;

        initPreds();
        this.candidateReceiver = new ArrayList<>();
    }

    protected ORQLCIWithEnergyAwareness(ORQLCIWithEnergyAwareness r) {
        super(r);

        this.baseDiscountGamma   = r.baseDiscountGamma;
        this.learningRate        = r.learningRate;
        this.discountFactor      = r.discountFactor;
        this.updateInterval      = r.updateInterval;
        this.gammaP              = r.gammaP;
        this.agingOmega          = r.agingOmega;
        this.qAgeTimeUnit        = r.qAgeTimeUnit;
        this.epsilonStart        = r.epsilonStart;
        this.epsilonEnd          = r.epsilonEnd;
        this.epsilonDecayType    = r.epsilonDecayType;
        this.simulationTotalTime = r.simulationTotalTime;
        this.currentEpsilon      = r.epsilonStart; // setiap node mulai dari epsilon awal

        initPreds();
        this.candidateReceiver = new ArrayList<>();
    }

    // =========================================================================
    // PROPHET ENCOUNTER PROBABILITY
    // =========================================================================

    private void initPreds() {
        this.preds         = new LinkedHashMap<>();
        this.lastAgeUpdate = 0.0;
    }

    private void ageDeliveryPreds() {
        double now      = SimClock.getTime();
        double timeDiff = (now - lastAgeUpdate) / SEC_IN_TU;
        if (timeDiff <= 0) return;

        double mult = Math.pow(gammaP, timeDiff);
        for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
            e.setValue(e.getValue() * mult);
        }
        lastAgeUpdate = now;
    }

    /** Update P(A->other) saat bertemu node lain. */
    private void updateEncounterProb(DTNHost other) {
        ageDeliveryPreds();
        double oldVal = preds.getOrDefault(other, 0.0);
        preds.put(other, oldVal + (1.0 - oldVal) * P_INIT);
    }

    /** Update P(A->C) secara transitif melalui B: P(A->C) += (1-P(A->C)) * P(A->B) * P(B->C) * beta */
    private void updateTransitivity(DTNHost nodeB, ORQLCIWithEnergyAwareness routerB) {
        ageDeliveryPreds();
        routerB.ageDeliveryPreds();

        double pAB = preds.getOrDefault(nodeB, 0.0);
        if (pAB == 0.0) return;

        for (Map.Entry<DTNHost, Double> entry : routerB.preds.entrySet()) {
            DTNHost nodeC = entry.getKey();
            if (nodeC.equals(getHost())) continue;
            double pBC    = entry.getValue();
            double pACold = preds.getOrDefault(nodeC, 0.0);
            preds.put(nodeC, pACold + (1.0 - pACold) * pAB * pBC * BETA);
        }
    }

    /** P(this node -> host), dengan aging terlebih dahulu. */
    public double getPredFor(DTNHost host) {
        ageDeliveryPreds();
        return preds.getOrDefault(host, 0.0);
    }

    /** Peta encounter probability dalam format address -> prob, untuk berbagi ke tetangga. */
    public Map<Integer, Double> getEncounterProbMap() {
        ageDeliveryPreds();
        Map<Integer, Double> probMap = new HashMap<>();
        for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
            probMap.put(e.getKey().getAddress(), e.getValue());
        }
        return probMap;
    }

    // =========================================================================
    // CONTEXT FACTORS: BUFFER & ENERGY
    // =========================================================================

    /** Buffer Factor: rasio buffer bebas terhadap total buffer, [0, 1]. */
    private double getBufferFactor(DTNHost host) {
        MessageRouter router = host.getRouter();
        int cTotal = router.getBufferSize();
        if (cTotal <= 0 || cTotal == Integer.MAX_VALUE) return 1.0;

        long occupied = 0;
        for (Message m : router.getMessageCollection()) occupied += m.getSize();

        return Math.max(0.0, Math.min(1.0, 1.0 - (double) occupied / cTotal));
    }

    /**
     * Ambil EF dari node tetangga.
     * EF = trackedCurrentEnergy / maxEnergy (disync via comBus di QLearningRouterEnergyAware).
     */
    private double getEnergyFactorOf(DTNHost host) {
        MessageRouter r = host.getRouter();
        if (!(r instanceof ORQLCIWithEnergyAwareness)) return 1.0;
        return ((ORQLCIWithEnergyAwareness) r).getEnergyFactor();
    }

    /** P(relay -> destination) — diambil dari PRoPHET preds milik relay. */
    private double getEncounterProbToward(DTNHost relay, DTNHost destination) {
        MessageRouter r = relay.getRouter();
        if (!(r instanceof ORQLCIWithEnergyAwareness)) return 0.0;
        return ((ORQLCIWithEnergyAwareness) r).getPredFor(destination);
    }

    // =========================================================================
    // Q-TABLE UPDATE SAAT KONTAK
    // =========================================================================

    /**
     * Saat dua node bertemu, update Q-table langsung (on-contact, bukan via timer).
     * Untuk setiap destinasi yang relevan:
     *   - Jika other == destinasi: updateQDirect (reward = 1)
     *   - Jika relay: updateQRelay dengan gamma_d = gamma_base x BF x EF  (Eq. 9)
     */
    private void updateQTableOnContact(DTNHost other, ORQLCIWithEnergyAwareness otherRouter) {
        ageQTable(SEC_IN_TU);
        otherRouter.ageQTable(SEC_IN_TU);

        int    otherAddr       = other.getAddress();
        double bfOther         = getBufferFactor(other);
        double efOther         = getEnergyFactorOf(other); // EF dari THE ONE energy system
        double dynamicDiscount = baseDiscountGamma * bfOther * efOther; // gamma_d

        Map<Integer, Double> otherProbMap = otherRouter.getEncounterProbMap();

        // Himpunan destinasi yang relevan: dari pesan di buffer + dari Q-table yang ada
        Set<Integer> relevantDests = new HashSet<>();
        for (Message m : getMessageCollection()) relevantDests.add(m.getTo().getAddress());
        relevantDests.addAll(qvalues.keySet());

        for (int destAddr : relevantDests) {
            if (destAddr == getHost().getAddress()) continue; // skip diri sendiri

            if (otherAddr == destAddr) {
                // other ADALAH destinasi — reward langsung = 1
                updateQDirect(destAddr, otherAddr);
            } else {
                // other adalah relay — bootstrap dari Q-table tetangga
                double neighborMaxQP = otherRouter.getNeighborMaxQPrime(destAddr, otherProbMap);
                updateQRelay(destAddr, otherAddr, dynamicDiscount, neighborMaxQP);
            }
        }
    }

    // =========================================================================
    // CONNECTION EVENT
    // =========================================================================

    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con); // -> QLearningRouterEnergyAware: connection history

        DTNHost other = con.getOtherNode(getHost());
        if (!(other.getRouter() instanceof ORQLCIWithEnergyAwareness)) return;

        ORQLCIWithEnergyAwareness otherRouter = (ORQLCIWithEnergyAwareness) other.getRouter();

        if (con.isUp()) {
            if (!candidateReceiver.contains(con)) candidateReceiver.add(con);
            updateEncounterProb(other);
            updateTransitivity(other, otherRouter);
            updateQTableOnContact(other, otherRouter); // update Q saat kontak
        } else {
            candidateReceiver.remove(con);
        }
    }

    // =========================================================================
    // RECEIVING GATE
    // =========================================================================

    /**
     * Gate penerimaan pesan:
     *   1. EnergyAwareRouter: tolak jika energi < 0 (radio sudah dimatikan)
     *   2. EF_THRESHOLD:      tolak relay jika EF kritis (tapi tetap terima pesan sendiri)
     */
    @Override
    protected int checkReceiving(Message m) {
        // Gate 1: EnergyAwareRouter — menolak jika currentEnergy < 0
        int superResult = super.checkReceiving(m);
        if (superResult != RCV_OK) return superResult;

        // Gate 2: jika EF di bawah threshold, jangan jadi relay
        // (kecuali kita adalah destinasi akhir pesan ini)
        if (getEnergyFactor() < EF_THRESHOLD && !getHost().equals(m.getTo())) {
            return DENIED_UNSPECIFIED;
        }

        return RCV_OK;
    }

    // =========================================================================
    // EPSILON DECAY
    // =========================================================================

    /**
     * Hitung epsilon saat ini berdasarkan progress simulasi.
     * Linear: epsilon = epsilonEnd + (epsilonStart - epsilonEnd) * (1 - progress)
     * Exponential: epsilon = epsilonEnd + (epsilonStart - epsilonEnd) * exp(-3 * progress)
     */
    private double calculateCurrentEpsilon() {
        double progress = Math.min(1.0, Math.max(0.0,
                SimClock.getTime() / simulationTotalTime));

        if ("exponential".equalsIgnoreCase(epsilonDecayType)) {
            return epsilonEnd + (epsilonStart - epsilonEnd) * Math.exp(-3.0 * progress);
        } else {
            return epsilonEnd + (epsilonStart - epsilonEnd) * (1.0 - progress);
        }
    }

    // =========================================================================
    // UPDATE LOOP
    // =========================================================================

    /**
     * Alur update setiap tick simulasi:
     *
     * super.update() memanggil EnergyAwareRouter.update() yang menjalankan:
     *   (1) ActiveRouter.update()             -> TTL expiry, transfer completion
     *   (2) reduceSendingAndScanningEnergy()  -> drain energi THE ONE; matikan radio jika 0
     *   (3) exchangeDeliverableMessages()     -> kirim langsung ke final destination
     *   (4) tryAllMessagesToAllConnections()  -> no-op (diblokir di QLearningRouterEnergyAware)
     *
     * Setelah super.update(), kita lanjut dengan:
     *   (5) epsilon update
     *   (6) tryOtherMessage() -> Q-learning + epsilon-greedy + PRoPHET fallback
     *   (7) ageQTable() periodik
     */
    @Override
    public void update() {
        super.update(); // (1)(2)(3)(4) - lihat komentar di atas

        if (isTransferring() || !canStartTransfer()) return;

        this.currentEpsilon = calculateCurrentEpsilon(); // (5)

        tryOtherMessage(); // (6)

        // (7) Periodik aging Q-table
        double now = SimClock.getTime();
        if ((now - lastUpdateTime) >= updateInterval) {
            lastUpdateTime = now;
            ageQTable(SEC_IN_TU);
        }
    }

    // =========================================================================
    // FORWARDING LOGIC: Q-Learning + Epsilon-Greedy + PRoPHET Fallback
    // =========================================================================

    /**
     * Pilih (pesan, koneksi) terbaik untuk dikirim sekarang.
     *
     * Prioritas keputusan per (pesan, kandidat):
     *   1. other == destinasi pesan    -> forward, score = +Infinity
     *   2. Ada Q-entry untuk dest ini:
     *      - explore (random < epsilon) -> forward, score = random
     *      - greedy                     -> forward hanya jika bestAction == other
     *   3. Belum ada Q-entry           -> fallback ke P(other -> dest) dari PRoPHET
     *
     * Gate sebelum forward:
     *   - other sedang transferring  -> skip
     *   - buffer other penuh         -> skip
     *   - EF other < threshold       -> skip (kecuali other adalah destinasi)
     */
    private void tryOtherMessage() {
        Collection<Message> msgCollection = getMessageCollection();
        if (msgCollection.isEmpty() || candidateReceiver.isEmpty()) return;

        Tuple<Message, Connection> bestCandidate = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (Connection con : candidateReceiver) {
            if (!con.isUp()) continue;

            DTNHost other = con.getOtherNode(getHost());
            if (!(other.getRouter() instanceof ORQLCIWithEnergyAwareness)) continue;

            ORQLCIWithEnergyAwareness otherRouter = (ORQLCIWithEnergyAwareness) other.getRouter();
            if (otherRouter.isTransferring()) continue;

            // Ambil EF tetangga — dari THE ONE energy system (via trackedCurrentEnergy)
            double  efOther = getEnergyFactorOf(other);
            boolean explore = (Math.random() < this.currentEpsilon);

            for (Message m : msgCollection) {
                if (otherRouter.hasMessage(m.getId())) continue;
                if (otherRouter.getFreeBufferSize() < m.getSize()) continue;

                // Gate energi sisi pengirim: tidak forward ke relay kritis
                // kecuali relay adalah destinasi akhir pesan ini
                if (efOther < EF_THRESHOLD && !other.equals(m.getTo())) continue;

                int     destAddr      = m.getTo().getAddress();
                boolean shouldForward = false;
                double  score         = Double.NEGATIVE_INFINITY;

                if (m.getTo() == other) {
                    // Kasus 1: direct delivery — selalu forward
                    shouldForward = true;
                    score         = Double.POSITIVE_INFINITY;

                } else if (hasQEntry(destAddr)) {
                    // Kasus 2: ada pengetahuan Q-Learning
                    if (explore) {
                        shouldForward = true;
                        score         = Math.random(); // eksplorasi acak
                    } else {
                        int bestAction = getBestAction(destAddr);
                        if (other.getAddress() == bestAction) {
                            shouldForward = true;
                            score         = getQV(destAddr, bestAction);
                        }
                    }

                } else {
                    // Kasus 3: belum ada Q-entry, fallback ke PRoPHET encounter prob
                    double encProb = getEncounterProbToward(other, m.getTo());
                    if (encProb > 0.0) {
                        shouldForward = true;
                        score         = encProb;
                    }
                }

                if (shouldForward && score > bestScore) {
                    bestScore     = score;
                    bestCandidate = new Tuple<>(m, con);
                }
            }
        }

        if (bestCandidate != null) {
            startTransfer(bestCandidate.getKey(), bestCandidate.getValue());
        }
    }

    // =========================================================================
    // REPLICATE
    // =========================================================================

    @Override
    public ORQLCIWithEnergyAwareness replicate() {
        return new ORQLCIWithEnergyAwareness(this);
    }

    // =========================================================================
    // GETTERS & toString
    // =========================================================================

    public double getCurrentEpsilon()     { return currentEpsilon; }
    public int    getEncounterProbCount() { return preds.size(); }

    @Override
    public String toString() {
        double ef     = getEnergyFactor();
        String status = isDead() ? "DEAD" : (ef < EF_THRESHOLD ? "CRITICAL" : "OK");
        return super.toString() + String.format(
                " [eps=%.3f, EF=%.2f(%s), preds=%d, %s]",
                currentEpsilon, ef, status, preds.size(), getQTableStats());
    }
}
