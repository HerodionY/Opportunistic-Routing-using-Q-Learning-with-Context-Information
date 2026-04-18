package routing;

import core.*;
import java.util.*;
import reinforcement.*;
import report.RewardTimeReport;

/**
 * CCRouting - Optimized ORQLCI Implementation.
 * Perbaikan: Dynamic State (Context-Aware), Oracle Prevention,
 * dan Integrasi Fusion Score yang lebih presisi.
 */
public class CCRouting extends QLearningRouter {

    private double updateInterval;
    private double lastUpdateTime = 0;

    // Q-Learning Core
    private QLearning ql;
    private IExplorationPolicy explorationPolicy;
    private int totalState;
    private int totalAction;
    private Map<DTNHost, Integer> visitCount;

    // PRoPHET Parameters
    private int secondsInTimeUnit = 30;
    private double beta = 0.25;
    private double lastAgeUpdate = 0.0;
    private Map<DTNHost, Double> preds;
    private double pInit = 0.75;
    private double gammaProphet = 0.98;

    // ORQLCI Fusion Weights
    private final double fusionWeightRL = 0.8;
    private final double fusionWeightProphet = 0.1;
    private final double fusionWeightBuffer = 0.1;

    // Learning Parameters
    private double baseDiscountGamma = 0.6;
    private double learningCoeff = 0.8;

    // Data Structures
    // Sekarang menggunakan Map<Integer, List<Integer>> untuk menghindari Overwrite
    // Bug
    private Map<Integer, List<Integer>> pendingRewards;
    private List<Connection> candidateReceiver;

    private static final String CCROUTING_NS = "CCRouting";
    private static final String UPDATE_INTERVAL = "updateInterval";
    private static final String TOTAL_STATE = "totalState";
    private static final String TOTAL_ACTION = "totalAction";

    public CCRouting(Settings s) {
        super(s);
        Settings ccSettings = new Settings(CCROUTING_NS);
        updateInterval = ccSettings.getInt(UPDATE_INTERVAL);
        totalState = ccSettings.getInt(TOTAL_STATE);
        totalAction = ccSettings.getInt(TOTAL_ACTION);

        initPreds();
        initQL();

        this.pendingRewards = new LinkedHashMap<>();
        this.candidateReceiver = new ArrayList<>();
    }

    protected CCRouting(CCRouting r) {
        super(r);
        this.updateInterval = r.updateInterval;
        this.totalState = r.totalState;
        this.totalAction = r.totalAction;
        this.baseDiscountGamma = r.baseDiscountGamma;
        this.learningCoeff = r.learningCoeff;

        initPreds();
        initQL();

        this.pendingRewards = new HashMap<>();
        this.candidateReceiver = new ArrayList<>();
    }

    protected void initQL() {
        this.explorationPolicy = new EpsilonGreedyExploration(0.1);
        this.ql = new QLearning(totalState, totalAction, this.explorationPolicy, false);
        this.visitCount = new LinkedHashMap<>();
    }

    private void initPreds() {
        this.preds = new LinkedHashMap<>();
        this.lastAgeUpdate = 0.0;
    }

    // --- CONTEXT: DYNAMIC STATE CALCULATION ---

    /**
     * Menentukan State berdasarkan Buffer Factor (Self-Awareness)
     * s0: Lega (>70%), s1: Sedang (30-70%), s2: Kritis (<30%)
     */
    private int calculateCurrentState() {
        double bf = getBufferFactor(getHost());
        if (bf > 0.7)
            return 0;
        if (bf > 0.3)
            return 1;
        return 2;
    }

    // --- PROPHET LOGIC ---

    public double getPredFor(DTNHost host) {
        ageDeliveryPreds();
        return preds.getOrDefault(host, 0.0);
    }

    private void ageDeliveryPreds() {
        double currentTime = SimClock.getTime();
        double timeDiff = (currentTime - this.lastAgeUpdate) / secondsInTimeUnit;
        if (timeDiff <= 0)
            return;

        double mult = Math.pow(gammaProphet, timeDiff);
        for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
            e.setValue(e.getValue() * mult);
        }
        this.lastAgeUpdate = currentTime;
    }

    // --- Perhitungan Buffer Factor (Equations 7) ---

    private double getBufferFactor(DTNHost host) {
        MessageRouter router = host.getRouter();
        int cTotal = router.getBufferSize();
        if (cTotal <= 0 || cTotal == Integer.MAX_VALUE)
            return 1.0;

        long occupied = 0;
        for (Message m : router.getMessageCollection()) {
            occupied += m.getSize();
        }
        double bf = 1.0 - ((double) occupied / cTotal);
        return Math.max(0.0, Math.min(1.0, bf));
    }

    private double getFusionScore(Message m, DTNHost other) {
        int destAddr = m.getTo().getAddress();
        int s = calculateCurrentState();
        double rlScore = ql.getQV(destAddr, s, other.getAddress());

        double prophetDelta = getPredFor(other) - getPredFor(m.getTo());
        double bf = getBufferFactor(other);

        return (fusionWeightRL * rlScore) +
                (fusionWeightProphet * Math.max(0, prophetDelta)) +
                (fusionWeightBuffer * bf);
    }

    // --- ROUTER INTERACTION ---

    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);
        DTNHost otherNode = con.getOtherNode(getHost());

        if (con.isUp()) {
            if (!this.pendingRewards.containsKey(otherNode.getAddress())) {
                this.pendingRewards.put(otherNode.getAddress(), new ArrayList<>());
            }
            this.candidateReceiver.add(con);

            // Update PRoPHET
            double oldValue = getPredFor(otherNode);
            preds.put(otherNode, oldValue + (1 - oldValue) * pInit);
        } else {
            this.candidateReceiver.remove(con);
        }
    }

    @Override
    public void update() {
        super.update();
        if (isTransferring() || !canStartTransfer())
            return;

        if (exchangeDeliverableMessages() != null)
            return;

        tryOtherMessage();

        double currentTime = SimClock.getTime();
        if ((currentTime - lastUpdateTime) >= updateInterval) {
            lastUpdateTime = currentTime;
            ql.ageQTable();

            // Hanya update untuk node yang SAAT INI terkoneksi
            for (Connection con : candidateReceiver) {
                DTNHost other = con.getOtherNode(getHost());
                int otherAddr = other.getAddress();

                List<Integer> dests = pendingRewards.get(otherAddr);
                if (dests == null || dests.isEmpty())
                    continue;

                CCRouting othRouter = (CCRouting) other.getRouter();
                int totalVisit = visitCount.getOrDefault(other, 0) + 1;
                visitCount.put(other, totalVisit);

                double pEncounter = getPredFor(other);
                double bf = getBufferFactor(other);
                int s = calculateCurrentState(); // Ambil state saat ini

                for (int destAddr : dests) {
                    double reward = (otherAddr == destAddr) ? 1.0 : 0.0;
                    double neighborMaxQPrime = othRouter.getQl().getNeighborMaxQPrime(destAddr, pEncounter);

                    this.ql.setLearningRate(totalVisit, learningCoeff);
                    this.ql.setDiscountFactorDynamic(baseDiscountGamma, bf);

                    // Update: s_sekarang (0-2), action (alamat node tetangga)
                    double[] metrics = this.ql.UpdateState(destAddr, s, otherAddr, reward, neighborMaxQPrime, this, other);
                    RewardTimeReport.addReward(s, metrics[0], metrics[1], metrics[2]);
                }
                dests.clear(); // Bersihkan setelah reward diproses
            }
        }
    }

    @Override
    protected Connection exchangeDeliverableMessages() {
        List<Connection> connections = getConnections();
        if (connections.isEmpty()) return null;

        @SuppressWarnings(value = "unchecked")
        Tuple<Message, Connection> t = tryMessagesForConnected(sortByQueueMode(getMessagesForConnected()));

        if (t != null) {
            Message m = t.getKey();
            DTNHost other = t.getValue().getOtherNode(getHost());
            this.pendingRewards.get(other.getAddress()).add(m.getTo().getAddress());
            return t.getValue(); // started transfer
        }

        // ask messages from connected
        for (Connection con : connections) {
            if (con.getOtherNode(getHost()).requestDeliverableMessages(con)) {
                return con;
            }
        }
        return null;
    }

    private void tryOtherMessage() {
        Collection<Message> msgCollection = getMessageCollection();
        if (msgCollection.isEmpty() || candidateReceiver.isEmpty())
            return;

        int s = calculateCurrentState();

        for (Connection con : candidateReceiver) {
            DTNHost other = con.getOtherNode(getHost());
            CCRouting othRouter = (CCRouting) other.getRouter();
            if (othRouter.isTransferring())
                continue;

            List<Tuple<Message, Connection>> potentials = new ArrayList<>();
            for (Message m : msgCollection) {
                if (othRouter.hasMessage(m.getId()))
                    continue;
                if (othRouter.getFreeBufferSize() < m.getSize())
                    continue;

                int destAddr = m.getTo().getAddress();

                // Keputusan Q-Learning Policy (Menggunakan State Dinamis)
                int action = this.ql.GetAction(destAddr, s, null, false);

                if (action == other.getAddress()) {
                    potentials.add(new Tuple<>(m, con));
                }
            }

            if (!potentials.isEmpty()) {
                Collections.sort(potentials, (t1, t2) -> {
                    double s1 = getFusionScore(t1.getKey(), t1.getValue().getOtherNode(getHost()));
                    double s2 = getFusionScore(t2.getKey(), t2.getValue().getOtherNode(getHost()));
                    return Double.compare(s2, s1);
                });

                Tuple<Message, Connection> best = potentials.get(0);
                if (startTransfer(best.getKey(), best.getValue()) == MessageRouter.RCV_OK) {
                    this.pendingRewards.get(other.getAddress()).add(best.getKey().getTo().getAddress());
                    break;
                }
            }
        }
    }

    @Override
    public CCRouting replicate() {
        return new CCRouting(this);
    }

    @Override
    public Map<Integer, Tuple<DTNHost, List<Integer>>> getMapWaitForReward() {
        return null;
    }

    public QLearning getQl() {
        return this.ql;
    }
}