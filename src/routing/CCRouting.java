package routing;

import core.*;
import java.util.*;
import reinforcement.*;

/**
 * CCRouting - Final Clean Version for ORQLCI Research.
 * Mengimplementasikan Q-Learning dengan bobot konteks dinamis
 * (Buffer Factor & Encounter Probability).
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

    // PRoPHET Parameters (Context: Encounter Probability)
    private int secondsInTimeUnit = 30;
    private double beta = 0.25;
    private double lastAgeUpdate = 0.0;
    private Map<DTNHost, Double> preds; // Encounter Probabilities (P)
    private double pInit = 0.75;
    private double gammaProphet = 0.98;

    // ORQLCI Fusion Weights (Decision Making)
    private final double fusionWeightRL = 0.8;
    private final double fusionWeightProphet = 0.1;
    private final double fusionWeightBuffer = 0.1;

    // Learning Parameters
    private double baseDiscountGamma = 0.6; // Base gamma untuk Persamaan (7)
    private double learningCoeff = 0.8; // Koefisien Alpha

    // Data Structures
    private Map<Integer, Tuple<DTNHost, List<Integer>>> waitForReward;
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

        this.waitForReward = new LinkedHashMap<>();
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

        this.waitForReward = new HashMap<>();
        this.candidateReceiver = new ArrayList<>();
    }

    protected void initQL() {
        this.explorationPolicy = new EpsilonGreedyExploration(0.989);
        this.ql = new QLearning(totalState, totalAction, this.explorationPolicy, false);
        this.visitCount = new LinkedHashMap<>();
    }

    private void initPreds() {
        this.preds = new LinkedHashMap<>();
        this.lastAgeUpdate = 0.0;
    }

    // --- PROPHET LOGIC ---

    private void updateDeliveryPredFor(DTNHost host) {
        double oldValue = getPredFor(host);
        double newValue = oldValue + (1 - oldValue) * pInit;
        preds.put(host, newValue);
    }

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

    private void updateTransitivePreds(DTNHost host) {
        MessageRouter otherRouter = host.getRouter();
        if (!(otherRouter instanceof CCRouting))
            return;

        double pForHost = getPredFor(host);
        Map<DTNHost, Double> othersPreds = ((CCRouting) otherRouter).preds;

        for (Map.Entry<DTNHost, Double> e : othersPreds.entrySet()) {
            DTNHost targetNode = e.getKey();
            if (targetNode == getHost())
                continue;

            double pOld = getPredFor(targetNode);
            double pNew = pOld + (1 - pOld) * pForHost * e.getValue() * beta;
            preds.put(targetNode, pNew);
        }
    }

    // --- CONTEXT CALCULATION (Equations 7 & Fusion) ---

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
        double rlScore = ql.getQV(destAddr, 0, other.getAddress());

        double prophetDelta = getPredFor(other) - getPredFor(m.getTo());
        if (prophetDelta < 0)
            prophetDelta = 0;

        double bf = getBufferFactor(other);

        return (fusionWeightRL * rlScore) + (fusionWeightProphet * prophetDelta) + (fusionWeightBuffer * bf);
    }

    // --- ROUTER INTERACTION ---

    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);
        DTNHost myHost = getHost();
        DTNHost otherNode = con.getOtherNode(myHost);

        if (con.isUp()) {
            if (!this.waitForReward.containsKey(otherNode.getAddress())) {
                this.waitForReward.put(otherNode.getAddress(), new Tuple<>(otherNode, new ArrayList<>()));
            }
            this.candidateReceiver.add(con);
            updateDeliveryPredFor(otherNode);
            updateTransitivePreds(otherNode);
        } else {
            this.candidateReceiver.remove(con);
        }
    }

    @Override
    public void update() {
        super.update();

        if (isTransferring() || !canStartTransfer())
            return;

        // Prioritaskan pengiriman ke tujuan akhir
        if (exchangeDeliverableMessages() != null)
            return;

        // Jalankan logika forwarding relay
        tryOtherMessage();

        // ORQLCI Learning Update Cycle
        double currentTime = SimClock.getTime();
        if ((currentTime - lastUpdateTime) >= updateInterval) {
            lastUpdateTime = currentTime;

            // Ageing Q-Table (Agar info lama meluruh)
            ql.ageQTable();

            for (Map.Entry<Integer, Tuple<DTNHost, List<Integer>>> entry : waitForReward.entrySet()) {
                List<Integer> sentDestinations = entry.getValue().getValue();
                if (sentDestinations == null || sentDestinations.isEmpty())
                    continue;

                DTNHost other = entry.getValue().getKey();
                CCRouting othRouter = (CCRouting) other.getRouter();

                int totalVisit = visitCount.getOrDefault(other, 0) + 1;
                visitCount.put(other, totalVisit);

                double pEncounter = getPredFor(other);
                double bf = getBufferFactor(other);

                for (int destAddr : sentDestinations) {
                    double reward = (other.getAddress() == destAddr) ? 1.0 : 0.0;

                    // Persamaan (10): Ambil nilai masa depan yang sudah difilter PROPHET
                    double neighborMaxQPrime = othRouter.getQl().getNeighborMaxQPrime(destAddr, pEncounter);

                    // Update parameters
                    this.ql.setLearningRate(totalVisit, learningCoeff);
                    this.ql.setDiscountFactorDynamic(baseDiscountGamma, bf); // Persamaan (7)

                    // Persamaan (5) & (9): Eksekusi update ke tabel
                    this.ql.UpdateState(destAddr, 0, other.getAddress(), reward, neighborMaxQPrime, this, other);
                }
                sentDestinations.clear();
            }
        }
    }

    private void tryOtherMessage() {
        Collection<Message> msgCollection = getMessageCollection();
        if (msgCollection.isEmpty() || candidateReceiver.isEmpty())
            return;

        for (Connection con : candidateReceiver) {
            DTNHost other = con.getOtherNode(getHost());
            CCRouting othRouter = (CCRouting) other.getRouter();

            if (othRouter.isTransferring())
                continue;

            List<Tuple<Message, Connection>> potentials = new ArrayList<>();

            for (Message m : msgCollection) {
                if (othRouter.hasMessage(m.getId()))
                    continue;

                // Cek sisa memori tetangga
                if (othRouter.getFreeBufferSize() < m.getSize())
                    continue;

                int destAddr = m.getTo().getAddress();

                // Keputusan Q-Learning Policy
                int action = this.ql.GetAction(destAddr, 0, this.waitForReward, false);

                if (action == other.getAddress()) {
                    potentials.add(new Tuple<>(m, con));
                }
            }

            if (!potentials.isEmpty()) {
                // Sorting berdasarkan Fusion Score (Weighted RL + Prophet + Buffer)
                Collections.sort(potentials, (t1, t2) -> {
                    double s1 = getFusionScore(t1.getKey(), t1.getValue().getOtherNode(getHost()));
                    double s2 = getFusionScore(t2.getKey(), t2.getValue().getOtherNode(getHost()));
                    return Double.compare(s2, s1);
                });

                Tuple<Message, Connection> best = potentials.get(0);
                if (startTransfer(best.getKey(), best.getValue()) == MessageRouter.RCV_OK) {
                    this.waitForReward.get(other.getAddress()).getValue().add(best.getKey().getTo().getAddress());
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
        return this.waitForReward;
    }

    public QLearning getQl() {
        return this.ql;
    }
}