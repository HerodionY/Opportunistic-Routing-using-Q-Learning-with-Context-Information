package routing;

import core.*;
import java.util.*;

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
    private static final double GAMMA_P = 0.98;
    private static final double BETA = 0.25;
    private static final int SEC_IN_TU = 30; // time unit = 30s

    private Map<DTNHost, Double> preds;
    private double lastAgeUpdate = 0.0;

    // =========================================================================
    // LEARNING PARAMETERS
    // =========================================================================
    private double baseDiscountGamma;
    private double learningCoeff;

    /**
     * Algorithm 2 directional forwarding threshold (Table 1: 45 degrees).
     * Forward to relay rn when cos(Vdes, Vm) >= cos(delta).
     */
    private static final double DIRECTION_THRESHOLD_DEGREES = 45.0;
    private static final double DIRECTION_THRESHOLD_COS = Math.cos(Math.toRadians(DIRECTION_THRESHOLD_DEGREES));

    // =========================================================================
    // TIMING
    // =========================================================================
    private double updateInterval;
    private double lastUpdateTime = 0.0;

    // =========================================================================
    // DATA STRUCTURES
    // =========================================================================
    private List<Connection> candidateReceiver;

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
        this.qAgeTimeUnit = SEC_IN_TU;

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
    }

    // =========================================================================
    // ENCOUNTER PROBABILITY (Section 3.1)
    // =========================================================================

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

    private void updateEncounterProb(DTNHost other) {
        ageDeliveryPreds();
        double oldVal = preds.getOrDefault(other, 0.0);
        preds.put(other, oldVal + (1.0 - oldVal) * P_INIT);
    }

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

    public Map<Integer, Double> getCurrentNeighborProbMap() {
        ageDeliveryPreds();
        Map<Integer, Double> probMap = new HashMap<>();
        for (Connection con : getHost().getConnections()) {
            if (!con.isUp()) {
                continue;
            }

            DTNHost neighbor = con.getOtherNode(getHost());
            probMap.put(neighbor.getAddress(), preds.getOrDefault(neighbor, 0.0));
        }
        return probMap;
    }

    // =========================================================================
    // BUFFER FACTOR (Eq.4)
    // =========================================================================

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
    // DIRECTIONAL FORWARDING (Algorithm 2 fallback)
    // =========================================================================

    private double getDirectionalCosine(DTNHost relay, DTNHost destination) {
        Coord relayLocation = relay.getLocation();
        Coord relayWaypoint = relay.getDestination();
        Coord destinationLocation = destination.getLocation();

        if (relayLocation == null || relayWaypoint == null || destinationLocation == null) {
            return -1.0;
        }

        double moveX = relayWaypoint.getX() - relayLocation.getX();
        double moveY = relayWaypoint.getY() - relayLocation.getY();
        double targetX = destinationLocation.getX() - relayLocation.getX();
        double targetY = destinationLocation.getY() - relayLocation.getY();

        double moveNorm = Math.hypot(moveX, moveY);
        if (moveNorm == 0.0) {
            return -1.0;
        }

        double targetNorm = Math.hypot(targetX, targetY);
        if (targetNorm == 0.0) {
            return 1.0;
        }

        return ((moveX * targetX) + (moveY * targetY)) / (moveNorm * targetNorm);
    }

    private void updateQTableOnContact(DTNHost other, CCRouting otherRouter) {
        ageQTable(SEC_IN_TU);

        int otherAddr = other.getAddress();

        // Paper Section 4.1 menggunakan alpha konstan.
        this.learningRate = learningCoeff;

        // Context info
        double bfOther = getBufferFactor(other);
        double dynamicDiscount = baseDiscountGamma * bfOther; // Eq.7

        Map<Integer, Double> otherProbMap = otherRouter.getEncounterProbMap();

        Set<Integer> relevantDests = new HashSet<>();
        for (Message m : getMessageCollection()) {
            relevantDests.add(m.getTo().getAddress());
        }

        for (int destAddr : relevantDests) {
            if (destAddr == getHost().getAddress())
                continue;

            if (otherAddr == destAddr) {
                updateQDirect(destAddr, otherAddr);
            } else {
                double neighborMaxQP = otherRouter.getNeighborMaxQPrime(destAddr, otherProbMap);
                updateQRelay(destAddr, otherAddr, dynamicDiscount, neighborMaxQP);
            }
        }
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
            if (!candidateReceiver.contains(con)) {
                candidateReceiver.add(con);
            }

            // encounter probability
            updateEncounterProb(other);

            // transitivity
            updateTransitivity(other, otherRouter);

            // Algorithm 1
            // Q-update HANYA di sini (tidak di tryOtherMessage)
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

    private void tryOtherMessage() {
        Collection<Message> msgCollection = getMessageCollection();
        if (msgCollection.isEmpty() || candidateReceiver.isEmpty()) {
            return;
        }

        ageQTable(SEC_IN_TU);

        Tuple<Message, Connection> bestCandidate = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (Connection con : candidateReceiver) {
            if (!con.isUp()) {
                continue;
            }

            DTNHost other = con.getOtherNode(getHost());
            CCRouting otherRouter = (CCRouting) other.getRouter();
            if (otherRouter.isTransferring()) {
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

                if (hasQEntry(destAddr)) {
                    int bestAction = getBestAction(destAddr);
                    if (other.getAddress() == bestAction) {
                        shouldForward = true;
                        candidateScore = getQV(destAddr, bestAction);
                    }
                } else {
                    double directionalCosine = getDirectionalCosine(other, m.getTo());
                    if (directionalCosine >= DIRECTION_THRESHOLD_COS) {
                        shouldForward = true;
                        candidateScore = directionalCosine;
                    }
                }

                if (m.getTo() == other) {
                    shouldForward = true;
                    candidateScore = Double.POSITIVE_INFINITY;
                }

                if (shouldForward && candidateScore > bestScore) {
                    bestScore = candidateScore;
                    bestCandidate = new Tuple<>(m, con);
                }
            }
        }

        if (bestCandidate == null) {
            return;
        }

        startTransfer(bestCandidate.getKey(), bestCandidate.getValue());
    }

    // =========================================================================
    // REPLICATE
    // =========================================================================

    @Override
    public CCRouting replicate() {
        return new CCRouting(this);
    }
}
