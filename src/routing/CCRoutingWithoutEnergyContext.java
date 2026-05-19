package routing;

import core.*;
import java.util.*;

public class CCRoutingWithoutEnergyContext extends QLearningRouterWithoutEnergyContext {

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

    private static final double P_INIT = 0.75;
    private static final double BETA = 0.25;
    private static final int SEC_IN_TU = 30;

    private static final double GAMMA_P_DEFAULT = 0.98;
    private static final double OMEGA_Q_DEFAULT = 0.98;

    private Map<DTNHost, Double> preds;
    private double lastAgeUpdate = 0.0;

    private double baseDiscountGamma;
    private double learningCoeff;
    private double epsilonStart;
    private double epsilonEnd;
    private String epsilonDecayType;
    private double currentEpsilon;
    private double simulationTotalTime;
    private double gammaP;
    private double updateInterval;
    private double lastUpdateTime = 0.0;

    private List<Connection> candidateReceiver;

    public CCRoutingWithoutEnergyContext(Settings s) {
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

        initPreds();
        initLocal();
    }

    protected CCRoutingWithoutEnergyContext(CCRouting r) {
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

    private double calculateCurrentEpsilon() {
        double now = SimClock.getTime();
        double progress = now / simulationTotalTime;
        progress = Math.min(1.0, Math.max(0.0, progress));

        if ("exponential".equalsIgnoreCase(epsilonDecayType)) {
            return epsilonEnd + (epsilonStart - epsilonEnd) * Math.exp(-3.0 * progress);
        } else {
            return epsilonEnd + (epsilonStart - epsilonEnd) * (1.0 - progress);
        }
    }

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

    private void updateEncounterProb(DTNHost other) {
        ageDeliveryPreds();

        double oldVal = preds.getOrDefault(other, 0.0);
        preds.put(other, oldVal + (1.0 - oldVal) * P_INIT);
    }

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

    private double getEncounterProbToward(DTNHost relay, DTNHost destination) {
        MessageRouter r = relay.getRouter();
        if (!(r instanceof CCRouting)) {
            return 0.0;
        }
        return ((CCRouting) r).getPredFor(destination);
    }

    private void updateQTableOnContact(DTNHost other, CCRouting otherRouter) {
        ageQTable(SEC_IN_TU);

        otherRouter.ageQTable(SEC_IN_TU);

        int otherAddr = other.getAddress();

        double bfOther = getBufferFactor(other);
        double dynamicDiscount = baseDiscountGamma * bfOther;

        Map<Integer, Double> otherProbMap = otherRouter.getEncounterProbMap();
        Set<Integer> relevantDests = new HashSet<>();
        for (Message m : getMessageCollection()) {
            relevantDests.add(m.getTo().getAddress());
        }
        relevantDests.addAll(qvalues.keySet());

        for (int destAddr : relevantDests) {
            if (destAddr == getHost().getAddress()) {
                continue;
            }

            if (otherAddr == destAddr) {
                updateQDirect(destAddr, otherAddr);
            } else {
                double neighborMaxQP = otherRouter.getNeighborMaxQPrime(destAddr, otherProbMap);
                updateQRelay(destAddr, otherAddr, dynamicDiscount, neighborMaxQP);
            }
        }
    }

    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);

        DTNHost other = con.getOtherNode(getHost());

        if (!(other.getRouter() instanceof CCRouting)) {
            return;
        }

        CCRouting otherRouter = (CCRouting) other.getRouter();

        if (con.isUp()) {

            if (!candidateReceiver.contains(con)) {
                candidateReceiver.add(con);
            }

            updateEncounterProb(other);

            updateTransitivity(other, otherRouter);

            updateQTableOnContact(other, otherRouter);

        } else {
            candidateReceiver.remove(con);
        }
    }

    @Override
    public void update() {
        super.update();

        if (isTransferring() || !canStartTransfer()) {
            return;
        }

        this.currentEpsilon = calculateCurrentEpsilon();

        if (exchangeDeliverableMessages() != null) {
            return;
        }

        tryOtherMessage();

        double now = SimClock.getTime();
        if ((now - lastUpdateTime) >= updateInterval) {
            lastUpdateTime = now;
            ageQTable(SEC_IN_TU);
        }
    }

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

                if (m.getTo() == other) {
                    shouldForward = true;
                    candidateScore = Double.POSITIVE_INFINITY;
                } else if (hasQEntry(destAddr)) {
                    if (explore) {
                        shouldForward = true;
                        candidateScore = Math.random();
                    } else {
                        int bestAction = getBestAction(destAddr);
                        if (other.getAddress() == bestAction) {
                            shouldForward = true;
                            candidateScore = getQV(destAddr, bestAction);
                        }
                    }
                } else {
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

    @Override
    public CCRouting replicate() {
        return new CCRouting(this);
    }

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