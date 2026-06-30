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
 * Base class untuk Q-Learning router yang menggunakan EnergyAwareRouter dari THE ONE
 * sebagai sistem manajemen energi.
 *
 * Menyediakan:
 *   - Sparse per-destination Q-table dengan aging (Eq. 9, 10, 11)
 *   - Connection history tracking
 *   - Energy factor (EF) yang disync via ModuleCommunicationBus
 *
 * Desain kunci:
 *   override tryAllMessagesToAllConnections() jadi no-op agar epidemic routing
 *   bawaan EnergyAwareRouter.update() tidak berjalan.
 *   Subclass (ORQLCIWithEnergyAwareness) menangani routing sendiri setelah super.update().
 *
 * Hierarki:
 *   EnergyAwareRouter (THE ONE) → QLearningRouterEnergyAware → ORQLCIWithEnergyAwareness
 */
public abstract class QLearningRouterEnergyAware extends EnergyAwareRouter {

    public static final String MESSAGE_TOPICS_S = "topic";

    // =========================================================================
    // Q-TABLE FIELDS
    // =========================================================================

    /** Q-values: destAddr -> (actionAddr -> q-value). Sparse, lazy-init. */
    protected Map<Integer, Map<Integer, Double>> qvalues;

    /** Timestamp terakhir update per-entry, untuk aging. */
    protected Map<Integer, Map<Integer, Double>> lastQUpdateTimes;

    protected double learningRate   = 0.8;   // alpha - learning coefficient
    protected double discountFactor = 0.6;   // gamma - base discount factor
    protected double agingOmega     = 0.98;  // omega - aging constant (Eq. 11)
    protected int    qAgeTimeUnit   = 30;    // time unit untuk aging (detik)

    // =========================================================================
    // CONNECTION HISTORY
    // =========================================================================

    protected Map<DTNHost, Double>         startTimestamps;
    protected Map<DTNHost, List<Duration>> connHistory;

    // =========================================================================
    // ENERGY TRACKING (synced dari EnergyAwareRouter via ModuleCommunicationBus)
    // =========================================================================

    /**
     * Energi maksimum: batas atas dari setting intialEnergy.
     * Dipakai sebagai denominator EF = trackedCurrentEnergy / maxEnergy.
     */
    protected double maxEnergy;

    /**
     * Energi saat ini yang ditrack dari comBus.
     * Diupdate setiap kali EnergyAwareRouter mengubah nilai di comBus
     * (via moduleValueChanged override di class ini).
     */
    protected double trackedCurrentEnergy;

    // =========================================================================
    // CONSTRUCTORS
    // =========================================================================

    public QLearningRouterEnergyAware(Settings s) {
        super(s);
        this.startTimestamps = new HashMap<>();
        this.connHistory     = new HashMap<>();
        initQTable();

        // Baca maxEnergy dari Group settings — namespace sama dengan yang dibaca EnergyAwareRouter
        double[] initEnergy       = s.getCsvDoubles(EnergyAwareRouter.INIT_ENERGY_S);
        this.maxEnergy            = initEnergy[initEnergy.length - 1]; // batas atas range
        this.trackedCurrentEnergy = this.maxEnergy; // asumsi penuh saat init
    }

    protected QLearningRouterEnergyAware(QLearningRouterEnergyAware r) {
        super(r);
        this.startTimestamps      = new HashMap<>();
        this.connHistory          = new HashMap<>();
        this.learningRate         = r.learningRate;
        this.discountFactor       = r.discountFactor;
        this.agingOmega           = r.agingOmega;
        this.qAgeTimeUnit         = r.qAgeTimeUnit;
        this.maxEnergy            = r.maxEnergy;
        // trackedCurrentEnergy akan diupdate via moduleValueChanged setelah init
        this.trackedCurrentEnergy = r.maxEnergy;
        initQTable();
    }

    // =========================================================================
    // ENERGY TRACKING VIA MODULECOMMUNICATIONBUS
    // =========================================================================

    /**
     * Dipanggil comBus setiap kali nilai ENERGY_VALUE_ID berubah (saat drain terjadi).
     * EnergyAwareRouter.moduleValueChanged() mengupdate private field-nya sendiri;
     * kita juga sync ke trackedCurrentEnergy untuk perhitungan EF.
     */
    @Override
    public void moduleValueChanged(String key, Object newValue) {
        super.moduleValueChanged(key, newValue); // update private currentEnergy di EnergyAwareRouter
        if (EnergyAwareRouter.ENERGY_VALUE_ID.equals(key)) {
            this.trackedCurrentEnergy = (Double) newValue;
        }
    }

    /**
     * Energy Factor dalam rentang [0, 1]:
     *   EF = trackedCurrentEnergy / maxEnergy
     */
    public double getEnergyFactor() {
        if (maxEnergy <= 0) return 1.0;
        return Math.max(0.0, Math.min(1.0, trackedCurrentEnergy / maxEnergy));
    }

    /** True jika energi node ini sudah habis. */
    public boolean isDead() {
        return trackedCurrentEnergy <= 0;
    }

    // =========================================================================
    // Q-TABLE METHODS
    // =========================================================================

    protected void initQTable() {
        this.qvalues          = new HashMap<>();
        this.lastQUpdateTimes = new HashMap<>();
    }

    /** Pastikan entry (dest, action) ada; buat dengan nilai 0.0 jika belum. */
    private void ensureQEntry(int destAddr, int actionAddr) {
        qvalues.putIfAbsent(destAddr, new HashMap<>());
        lastQUpdateTimes.putIfAbsent(destAddr, new HashMap<>());
        qvalues.get(destAddr).putIfAbsent(actionAddr, 0.0);
        lastQUpdateTimes.get(destAddr).putIfAbsent(actionAddr, SimClock.getTime());
    }

    private boolean isValidAddress(int addr) {
        return addr >= 0;
    }

    /**
     * Age satu Q-entry: Q_aged = Q * omega^(timeDiff / timeUnit)  (Eq. 11).
     * Hanya age entry yang sudah ada — tidak membuat entry baru.
     */
    private void ageQEntry(int destAddr, int actionAddr, int secInTimeUnit) {
        if (!isValidAddress(destAddr) || !isValidAddress(actionAddr)) return;
        if (secInTimeUnit <= 0) return;

        Map<Integer, Double> actionMap = qvalues.get(destAddr);
        if (actionMap == null || !actionMap.containsKey(actionAddr)) return;

        double now        = SimClock.getTime();
        double lastUpdate = lastQUpdateTimes.get(destAddr).get(actionAddr);
        double timeDiff   = (now - lastUpdate) / secInTimeUnit;
        if (timeDiff <= 0) return;

        double agedQ = actionMap.get(actionAddr) * Math.pow(agingOmega, timeDiff);
        actionMap.put(actionAddr, agedQ);
        lastQUpdateTimes.get(destAddr).put(actionAddr, now);
    }

    /** Baca Q-value untuk (dest, action), dengan aging terlebih dahulu. */
    public double getQV(int destAddr, int actionAddr) {
        if (!isValidAddress(destAddr) || !isValidAddress(actionAddr)) return 0.0;
        Map<Integer, Double> actionMap = qvalues.get(destAddr);
        if (actionMap == null || !actionMap.containsKey(actionAddr)) return 0.0;
        ageQEntry(destAddr, actionAddr, qAgeTimeUnit);
        return qvalues.get(destAddr).get(actionAddr);
    }

    /**
     * Update Q untuk direct delivery (relay == destinasi).
     * Reward = 1.0  (Eq. 10).
     */
    public void updateQDirect(int destAddr, int relayAddr) {
        if (!isValidAddress(destAddr) || !isValidAddress(relayAddr)) return;
        ensureQEntry(destAddr, relayAddr);
        double oldQ = qvalues.get(destAddr).get(relayAddr);
        double newQ = (1.0 - learningRate) * oldQ + learningRate * 1.0;
        qvalues.get(destAddr).put(relayAddr, newQ);
        lastQUpdateTimes.get(destAddr).put(relayAddr, SimClock.getTime());
    }

    /**
     * Update Q untuk relay hop (Eq. 9):
     *   Q(dest, relay) = (1-alpha)*Q + alpha * gamma_d * maxQ'
     *
     * @param dynamicDiscount  gamma_d = gamma_base x BF x EF
     * @param neighborMaxQP    max_n { Q_neighbor(dest,n) x P(neighbor->n) }
     */
    public void updateQRelay(int destAddr, int relayAddr,
                             double dynamicDiscount, double neighborMaxQP) {
        if (!isValidAddress(destAddr) || !isValidAddress(relayAddr)) return;
        ensureQEntry(destAddr, relayAddr);
        double oldQ = qvalues.get(destAddr).get(relayAddr);
        double newQ = (1.0 - learningRate) * oldQ
                    + learningRate * dynamicDiscount * neighborMaxQP;
        qvalues.get(destAddr).put(relayAddr, newQ);
        lastQUpdateTimes.get(destAddr).put(relayAddr, SimClock.getTime());
    }

    /** Age semua entry Q-table yang ada. */
    public void ageQTable(int secInTimeUnit) {
        if (secInTimeUnit <= 0) return;
        for (Map.Entry<Integer, Map<Integer, Double>> destEntry : qvalues.entrySet()) {
            int destAddr = destEntry.getKey();
            for (Integer actionAddr : new ArrayList<>(destEntry.getValue().keySet())) {
                ageQEntry(destAddr, actionAddr, secInTimeUnit);
            }
        }
    }

    /**
     * Hitung nilai bootstrap tetangga untuk Bellman update:
     *   max_n { Q(dest, n) x P(neighbor->n) }
     *
     * @param encounterProbs  encounter probability milik tetangga: nodeAddr -> prob
     */
    public double getNeighborMaxQPrime(int destAddr, Map<Integer, Double> encounterProbs) {
        if (!isValidAddress(destAddr)) return 0.0;
        if (encounterProbs == null || encounterProbs.isEmpty()) return 0.0;

        double maxVal = 0.0;
        for (Map.Entry<Integer, Double> entry : encounterProbs.entrySet()) {
            int neighborAddr = entry.getKey();
            if (!isValidAddress(neighborAddr)) continue;
            double val = getQV(destAddr, neighborAddr) * entry.getValue();
            if (val > maxVal) maxVal = val;
        }
        return maxVal;
    }

    private static final double Q_MEANINGFUL_THRESHOLD = 1e-4;

    /** Apakah ada Q-entry bermakna (> threshold) untuk destinasi ini? */
    public boolean hasQEntry(int destAddr) {
        if (!isValidAddress(destAddr)) return false;
        Map<Integer, Double> actionMap = qvalues.get(destAddr);
        if (actionMap == null || actionMap.isEmpty()) return false;
        for (Integer actionAddr : new ArrayList<>(actionMap.keySet())) {
            ageQEntry(destAddr, actionAddr, qAgeTimeUnit);
            if (qvalues.get(destAddr).get(actionAddr) > Q_MEANINGFUL_THRESHOLD) return true;
        }
        return false;
    }

    /** Address relay dengan Q-value tertinggi untuk suatu destinasi. */
    public int getBestAction(int destAddr) {
        if (!isValidAddress(destAddr)) return -1;
        Map<Integer, Double> actionMap = qvalues.get(destAddr);
        if (actionMap == null || actionMap.isEmpty()) return -1;

        int    bestAction = -1;
        double bestVal    = Double.NEGATIVE_INFINITY;
        for (Map.Entry<Integer, Double> entry : new ArrayList<>(actionMap.entrySet())) {
            ageQEntry(destAddr, entry.getKey(), qAgeTimeUnit);
            double q = qvalues.get(destAddr).get(entry.getKey());
            if (q > bestVal) { bestVal = q; bestAction = entry.getKey(); }
        }
        return bestAction;
    }

    /** Q-value tertinggi untuk suatu destinasi. */
    public double getBestQValue(int destAddr) {
        if (!isValidAddress(destAddr)) return 0.0;
        Map<Integer, Double> actionMap = qvalues.get(destAddr);
        if (actionMap == null || actionMap.isEmpty()) return 0.0;
        double best = 0.0;
        for (Map.Entry<Integer, Double> entry : new ArrayList<>(actionMap.entrySet())) {
            ageQEntry(destAddr, entry.getKey(), qAgeTimeUnit);
            double q = qvalues.get(destAddr).get(entry.getKey());
            if (q > best) best = q;
        }
        return best;
    }

    /** Statistik Q-table untuk debugging. */
    public String getQTableStats() {
        int    totalDests   = qvalues.size();
        int    totalEntries = 0;
        double totalQ       = 0.0;
        for (Map<Integer, Double> actionMap : qvalues.values()) {
            totalEntries += actionMap.size();
            for (Double q : actionMap.values()) totalQ += q;
        }
        double avgQ = totalEntries > 0 ? totalQ / totalEntries : 0.0;
        return String.format("Q[dests=%d, entries=%d, avgQ=%.4f]",
                             totalDests, totalEntries, avgQ);
    }

    // =========================================================================
    // CONNECTION HISTORY
    // =========================================================================

    @Override
    public void changedConnection(Connection con) {
        DTNHost peer = con.getOtherNode(getHost());
        if (con.isUp()) {
            startTimestamps.put(peer, SimClock.getTime());
        } else {
            if (startTimestamps.containsKey(peer)) {
                double start = startTimestamps.remove(peer);
                double end   = SimClock.getTime();
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
        List<Boolean> topics = new ArrayList<>();
        for (int i = 0; i < 5; i++) topics.add(Math.random() < 0.5);
        msg.addProperty(MESSAGE_TOPICS_S, topics);
        return super.createNewMessage(msg);
    }

    // =========================================================================
    // KUNCI ARSITEKTUR: Blokir epidemic routing dari EnergyAwareRouter.update()
    // =========================================================================

    /**
     * Override jadi no-op — blokir epidemic routing bawaan EnergyAwareRouter.
     *
     * Alur EnergyAwareRouter.update():
     *   (1) ActiveRouter.update()             -> TTL expiry, transfer completion  [DIJALANKAN]
     *   (2) reduceSendingAndScanningEnergy()  -> drain energi THE ONE             [DIJALANKAN]
     *   (3) exchangeDeliverableMessages()     -> kirim langsung ke final dest     [DIJALANKAN]
     *   (4) tryAllMessagesToAllConnections()  -> epidemic routing                 [DIBLOKIR di sini]
     *
     * Routing (4) diserahkan sepenuhnya ke ORQLCIWithEnergyAwareness.tryOtherMessage().
     */
    @Override
    protected Connection tryAllMessagesToAllConnections() {
        return null;
    }

    // =========================================================================
    // ABSTRACT
    // =========================================================================

    @Override
    public abstract QLearningRouterEnergyAware replicate();
}
