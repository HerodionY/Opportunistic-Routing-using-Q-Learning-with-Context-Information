package ORQLCI;

import core.*;
import routing.ActiveRouter;
import routing.MessageRouter;
import routing.RoutingInfo;

import java.util.*;

/**
 * ORQLCI-Q Routing
 * Opportunistic Routing using Q-Learning & Context Information
 *
 * Implements:
 * - Q-Learning TD update (destination-aware)
 * - Dynamic Discount Factor (Buffer Ratio)
 * - Encounter Probability (PRoPHET-style)
 * - Aging & Transitivity
 *
 * Author: Herodion Yulis Putra Anugrah
 */
public class ORQLCIRoutingV1 extends ActiveRouter {

    /* =======================
       Q-LEARNING PARAMETERS
       ======================= */
    private double alpha = 0.5;   // learning rate
    private double beta = 0.25;   // transitivity factor
    private double eta  = 0.98;   // aging factor

    /* =======================
       Q-TABLE: Q[d][s][x]
       ======================= */
    private Map<Integer, Map<Integer, Map<Integer, Double>>> Q;

    /* =======================
       ENCOUNTER PROBABILITY
       ======================= */
    private Map<Integer, Double> encounterProb;
    private Map<Integer, Double> lastEncounterTime;
    private static final double P_INIT = 0.75;

    /* =======================
       CONSTRUCTOR
       ======================= */
    public ORQLCIRoutingV1(Settings s) {
        super(s);
        init();
    }

    protected ORQLCIRoutingV1(ORQLCIRoutingV1 r) {
        super(r);
        this.alpha = r.alpha;
        this.beta = r.beta;
        this.eta = r.eta;
        init();
    }

    private void init() {
        Q = new HashMap<>();
        encounterProb = new HashMap<>();
        lastEncounterTime = new HashMap<>();
    }

    @Override
    public ORQLCIRoutingV1 replicate() {
        return new ORQLCIRoutingV1(this);
    }

    /* =======================
       Q-TABLE HELPERS
       ======================= */
    private double getQ(int d, int s, int x) {
        Q.putIfAbsent(d, new HashMap<>());
        Q.get(d).putIfAbsent(s, new HashMap<>());
        return Q.get(d).get(s).getOrDefault(x, 0.0);
    }

    private void setQ(int d, int s, int x, double val) {
        Q.putIfAbsent(d, new HashMap<>());
        Q.get(d).putIfAbsent(s, new HashMap<>());
        Q.get(d).get(s).put(x, val);
    }

    /* =======================
       CONNECTION EVENTS
       ======================= */
    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);

        if (con.isUp()) {
            DTNHost other = con.getOtherNode(getHost());
            int b = other.getAddress();

            // Encounter update (Eq. 1)
            double oldP = encounterProb.getOrDefault(b, 0.0);
            double newP = oldP + (1 - oldP) * P_INIT;
            encounterProb.put(b, newP);
            lastEncounterTime.put(b, SimClock.getTime());

            // Transitivity update (Eq. 3)
            ORQLCIRoutingV1 otherRouter =
                    (ORQLCIRoutingV1) other.getRouter();
            updateTransitivity(otherRouter);
        }
    }

    /* =======================
       AGING PROCESS (Eq. 2)
       ======================= */
    @Override
    public void update() {
        super.update();

        double now = SimClock.getTime();
        for (int b : encounterProb.keySet()) {
            double last = lastEncounterTime.getOrDefault(b, now);
            double t = now - last;
            double aged = encounterProb.get(b) * Math.pow(eta, t);
            encounterProb.put(b, aged);
        }

        if (!canStartTransfer() || isTransferring()) {
            return;
        }

        tryOtherMessages();
    }

    /* =======================
       TRANSITIVITY (Eq. 3)
       ======================= */
    private void updateTransitivity(ORQLCIRoutingV1 other) {
        int b = other.getHost().getAddress();
        double pab = encounterProb.getOrDefault(b, 0.0);

        for (int c : other.encounterProb.keySet()) {
            double pbc = other.encounterProb.get(c);
            double pac = encounterProb.getOrDefault(c, 0.0);
            double newPac = pac + (1 - pac) * pab * pbc * beta;
            encounterProb.put(c, newPac);
        }
    }

    /* =======================
       DYNAMIC DISCOUNT FACTOR
       ======================= */
    private double computeGamma(DTNHost relay) {
        MessageRouter router = relay.getRouter();
        double bufferSize = router.getBufferSize();
        double freeBuffer = router.getFreeBufferSize();
        double BF = (bufferSize > 0) ? (freeBuffer / bufferSize) : 1.0;
        return Math.max(0.1, BF);
    }

    /* =======================
       REWARD FUNCTION
       ======================= */
    private double computeReward(Message m, DTNHost relay) {
        if (relay.getAddress() == m.getTo().getAddress()) {
            return 1.0;
        }
        return 0.0;
    }

    /* =======================
       Q-LEARNING UPDATE
       ======================= */
    private void updateQ(
            Message m,
            DTNHost current,
            DTNHost relay,
            ORQLCIRoutingV1 relayRouter
    ) {
        int d = m.getTo().getAddress();
        int s = current.getAddress();
        int x = relay.getAddress();

        double R = computeReward(m, relay);

        // Terminal state (Eq. 8)
        if (R == 1.0) {
            double oldQ = getQ(d, s, x);
            double newQ = (1 - alpha) * oldQ + alpha * R;
            setQ(d, s, x, newQ);
            return;
        }

        // Future reward (Eq. 6)
        double maxFuture = 0.0;
        for (int y : relayRouter.encounterProb.keySet()) {
            double qxy = relayRouter.getQ(d, x, y);
            double pxy = relayRouter.encounterProb.get(y);
            maxFuture = Math.max(maxFuture, qxy * pxy);
        }

        double gamma = computeGamma(relay);
        double oldQ = getQ(d, s, x);

        // TD update (Eq. 7)
        double newQ = (1 - alpha) * oldQ +
                alpha * (R + gamma * maxFuture);

        setQ(d, s, x, newQ);
    }

    /* =======================
       MESSAGE FORWARDING
       ======================= */
    private void tryOtherMessages() {
        for (Connection con : getConnections()) {
            DTNHost other = con.getOtherNode(getHost());
            ORQLCIRoutingV1 otherRouter =
                    (ORQLCIRoutingV1) other.getRouter();

            for (Message m : getMessageCollection()) {
                if (otherRouter.hasMessage(m.getId())) {
                    continue;
                }

                if (startTransfer(m, con) == RCV_OK) {
                    updateQ(m, getHost(), other, otherRouter);
                    return;
                }
            }
        }
    }

    /* =======================
       ROUTING INFO (GUI)
       ======================= */
    @Override
    public RoutingInfo getRoutingInfo() {
        RoutingInfo ri = super.getRoutingInfo();
        ri.addMoreInfo(new RoutingInfo("Q-table size: " + Q.size()));
        ri.addMoreInfo(new RoutingInfo("Encounter entries: " + encounterProb.size()));
        return ri;
    }
}
