package routing;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;
import core.Tuple;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import reinforcement.EpsilonGreedyExploration;
import reinforcement.IExplorationPolicy;
import reinforcement.QLearning;

/**
 * ORQLCI Performance Router (legacy variant kept compile-safe).
 */
public class ORQLCIPerformanceRouter extends QLearningRouter {

    private static final String ORQLCI_NS = "ORQLCIPerformanceRouter";
    private static final String UPDATE_INTERVAL = "updateInterval";
    private static final String TOTAL_STATE = "totalState";
    private static final String TOTAL_ACTION = "totalAction";
    private static final String DROP_INTERVAL = "dropInterval";
    private static final String DROP_AMOUNT = "dropAmount";

    private static final double SMOOTHING_FACTOR = 0.20;
    private static final double P_INIT = 0.75;
    private static final double GAMMA_PROPHET = 0.98;
    private static final double LEARNING_COEFF = 0.8;

    private double updateInterval;
    private int msgReceived = 0;
    private int msgTransferred = 0;
    private double lastUpdateTime = 0;
    private double totalContactTime = 0;
    private List<Double> dataContact;
    private List<Double> listOfSumDataContact;
    private double dropInterval;
    private int dropAmount;
    private double lastDropTime = 0;
    private double cr = 0.0;
    private double ema = 0.0;

    private QLearning ql;
    private IExplorationPolicy explorationPolicy;
    private int totalState;
    private int totalAction;
    private Map<DTNHost, Double> totalRewardWithNode;
    private Map<DTNHost, Integer> visitCount;
    private int newState = 0;

    private int secondsInTimeUnit;
    private double lastAgeUpdate = 0.0;
    private Map<DTNHost, Double> preds;

    private Map<Integer, Tuple<DTNHost, List<Integer>>> waitForReward;
    private List<Connection> candidateReceiver;

    public ORQLCIPerformanceRouter(Settings s) {
        super(s);
        Settings orSettings = new Settings(ORQLCI_NS);
        this.updateInterval = orSettings.getInt(UPDATE_INTERVAL);
        this.totalState = orSettings.getInt(TOTAL_STATE);
        this.totalAction = orSettings.getInt(TOTAL_ACTION);
        this.dropInterval = orSettings.contains(DROP_INTERVAL) ? orSettings.getDouble(DROP_INTERVAL) : 0;
        this.dropAmount = orSettings.contains(DROP_AMOUNT) ? orSettings.getInt(DROP_AMOUNT) : 0;

        this.secondsInTimeUnit = 30;
        this.waitForReward = new HashMap<>();
        this.candidateReceiver = new ArrayList<>();
        this.dataContact = new ArrayList<>();
        this.listOfSumDataContact = new ArrayList<>();
        initPreds();
        initQL();
    }

    protected ORQLCIPerformanceRouter(ORQLCIPerformanceRouter r) {
        super(r);
        this.updateInterval = r.updateInterval;
        this.totalState = r.totalState;
        this.totalAction = r.totalAction;
        this.dropInterval = r.dropInterval;
        this.dropAmount = r.dropAmount;
        this.secondsInTimeUnit = r.secondsInTimeUnit;

        this.waitForReward = new HashMap<>();
        this.candidateReceiver = new ArrayList<>();
        this.dataContact = new ArrayList<>();
        this.listOfSumDataContact = new ArrayList<>();
        initPreds();
        initQL();
    }

    private void initQL() {
        this.explorationPolicy = new EpsilonGreedyExploration(0.989);
        this.ql = new QLearning(totalState, totalAction, this.explorationPolicy, false);
        this.totalRewardWithNode = new HashMap<>();
        this.visitCount = new HashMap<>();
    }

    private void initPreds() {
        this.preds = new HashMap<>();
        this.lastAgeUpdate = 0.0;
    }

    private void updateDeliveryPredFor(DTNHost host) {
        double oldValue = getPredFor(host);
        double newValue = oldValue + (1 - oldValue) * P_INIT;
        preds.put(host, newValue);
    }

    public double getPredFor(DTNHost host) {
        ageDeliveryPreds();
        return preds.getOrDefault(host, 0.0);
    }

    private void ageDeliveryPreds() {
        double now = SimClock.getTime();
        double timeDiff = (now - this.lastAgeUpdate) / secondsInTimeUnit;
        if (timeDiff <= 0) {
            return;
        }

        double mult = Math.pow(GAMMA_PROPHET, timeDiff);
        for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
            e.setValue(e.getValue() * mult);
        }
        this.lastAgeUpdate = now;
    }

    public void countCongestionRatio() {
        double dataEachContact = (totalContactTime > 0)
                ? (double) (this.msgReceived + this.msgTransferred) / totalContactTime : 0;
        this.dataContact.add(dataEachContact);

        double total = 0;
        for (double d : dataContact) {
            total += d;
        }
        this.listOfSumDataContact.add(total);

        double sumCr = 0;
        for (double d : listOfSumDataContact) {
            sumCr += d;
        }
        this.cr = sumCr / listOfSumDataContact.size();
    }

    public void countEma(double oLast) {
        this.ema = oLast * SMOOTHING_FACTOR + this.ema * (1 - SMOOTHING_FACTOR);
    }

    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);
        DTNHost other = con.getOtherNode(getHost());

        if (con.isUp()) {
            if (!this.waitForReward.containsKey(other.getAddress())) {
                this.waitForReward.put(other.getAddress(), new Tuple<>(other, new ArrayList<Integer>()));
            }
            if (this.waitForReward.get(other.getAddress()).getValue().isEmpty()) {
                this.candidateReceiver.add(con);
            }
            updateDeliveryPredFor(other);
        } else {
            this.totalContactTime += SimClock.getTime();
        }
    }

    @Override
    public void update() {
        super.update();

        if (isTransferring() || !canStartTransfer()) {
            return;
        }
        if (exchangeDeliverableMessages() != null) {
            return;
        }

        if (dropInterval > 0 && (SimClock.getTime() - lastDropTime) >= dropInterval) {
            lastDropTime = SimClock.getTime();
            dropMessages(dropAmount);
        }

        tryOtherMessage();

        if ((SimClock.getTime() - lastUpdateTime) >= updateInterval) {
            lastUpdateTime = SimClock.getTime();

            for (Map.Entry<Integer, Tuple<DTNHost, List<Integer>>> entry : waitForReward.entrySet()) {
                if (entry.getValue() == null || entry.getValue().getValue() == null || entry.getValue().getValue().isEmpty()) {
                    continue;
                }

                DTNHost other = entry.getValue().getKey();
                ORQLCIPerformanceRouter othRouter = (ORQLCIPerformanceRouter) other.getRouter();

                othRouter.countCongestionRatio();
                othRouter.countEma(othRouter.cr);
                double reward = (othRouter.ema > 0) ? 1.0 / othRouter.ema : 1.0;

                int totalVisit = visitCount.getOrDefault(other, 0) + 1;
                double totalReward = totalRewardWithNode.getOrDefault(other, 0.0) + reward;

                this.visitCount.put(other, totalVisit);
                this.totalRewardWithNode.put(other, totalReward);

                this.ql.setLearningRate(totalVisit, LEARNING_COEFF);
                this.ql.setDiscountFactor(totalReward);

                for (int destinationAddress : entry.getValue().getValue()) {
                    int action = this.ql.GetAction(destinationAddress, entry.getKey(), waitForReward, true);
                    this.ql.UpdateState(destinationAddress, entry.getKey(), action, reward, newState, this, other);
                }

                othRouter.msgReceived = 0;
                othRouter.msgTransferred = 0;
            }
        }
    }

    private Tuple<Message, Connection> tryOtherMessage() {
        List<Tuple<Message, Connection>> messages = new ArrayList<>();
        Collection<Message> msgCollection = getMessageCollection();

        Iterator<Connection> it = candidateReceiver.iterator();
        while (it.hasNext()) {
            Connection con = it.next();
            DTNHost other = con.getOtherNode(getHost());
            ORQLCIPerformanceRouter othRouter = (ORQLCIPerformanceRouter) other.getRouter();

            if (othRouter.isTransferring()) {
                continue;
            }

            List<Integer> sentDestinations = new ArrayList<>();
            for (Message m : msgCollection) {
                if (othRouter.hasMessage(m.getId())) {
                    continue;
                }

                int destinationAddress = m.getTo().getAddress();
                newState = this.ql.GetAction(destinationAddress, other.getAddress(), this.waitForReward, false);

                if (newState == other.getAddress()) {
                    if (m.getTo() == other || othRouter.getPredFor(m.getTo()) > this.getPredFor(m.getTo())) {
                        messages.add(new Tuple<>(m, con));
                        sentDestinations.add(destinationAddress);
                    }
                }
            }

            if (!sentDestinations.isEmpty()) {
                this.waitForReward.put(other.getAddress(), new Tuple<>(other, sentDestinations));
                it.remove();
                it = candidateReceiver.iterator();
            }
        }

        if (messages.isEmpty()) {
            return null;
        }
        return tryMessagesForConnected(messages);
    }

    private void dropMessages(int amount) {
        List<Message> msgs = new ArrayList<>(getMessageCollection());
        if (msgs.isEmpty()) {
            return;
        }

        Collections.sort(msgs, new Comparator<Message>() {
            @Override
            public int compare(Message m1, Message m2) {
                return Double.compare(m1.getReceiveTime(), m2.getReceiveTime());
            }
        });

        int dropped = 0;
        for (Message m : msgs) {
            if (dropped >= amount) {
                break;
            }
            deleteMessage(m.getId(), true);
            dropped++;
        }
    }

    @Override
    public Map<Integer, Tuple<DTNHost, List<Integer>>> getMapWaitForReward() {
        return this.waitForReward;
    }

    @Override
    public ORQLCIPerformanceRouter replicate() {
        return new ORQLCIPerformanceRouter(this);
    }
}
