package routing;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.ModuleCommunicationBus;
import core.NetworkInterface;
import core.Settings;
import core.SimClock;
import core.Tuple;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import reinforcement.EpsilonGreedyExploration;
import reinforcement.IExplorationPolicy;
import reinforcement.QLearning;

public class CCRouting extends QLearningRouter {

    private double updateInterval;

    // Variable untuk Congestion Ratio (tetap dipertahankan untuk metrik/log)
    private int msgReceived = 0;
    private int msgTransferred = 0;
    private int dataReceived = 0;
    private int dataTransferred = 0;
    private double lastUpdateTime = 0;
    private double totalContactTime = 0;
    private List<Double> dataContact;
    private List<Double> listOfSumDataContact;
    private static final double SMOOTHING_FACTOR = 0.20;
    private double cr = 0.0;
    private double ema = 0.0;

    // Variable untuk learning
    private QLearning ql;
    private IExplorationPolicy explorationPolicy;
    private int totalState;
    private int totalAction;
    private Map<DTNHost, Integer> visitCount;
    private int newState = 0;

    // PRoPHET delivery predictability state
    private int secondsInTimeUnit;
    private double beta;
    private double lastAgeUpdate = 0.0;
    private Map<DTNHost, Double> preds;
    private double pInit = P_INIT;
    private double gamma = GAMMA;
    private boolean prophetEnabled = true;
    private boolean prophetAllowNoInfo = true;
    private double prophetMinDelta = 0.0;
    private boolean bufferAwareEnabled = false;
    private boolean fusionEnabled = false;
    private double fusionWeightRL = 0.0;
    private double fusionWeightProphet = 0.0;
    private double fusionWeightBuffer = 0.0;
    private double fusionWeightEnergy = 0.0;

    // Energy-aware context (proposal extension)
    public static final String ENERGY_VALUE_ID = "Energy.value";
    public static final String ENERGY_CAPACITY_ID = "Energy.capacity";
    private boolean energyAwareEnabled = true;
    private double[] initialEnergy = new double[] {1000.0};
    private double initialEnergyCapacity = 1000.0;
    private double currentEnergy = 1000.0;
    private double scanEnergy = 0.1;
    private double transmitEnergy = 0.1;
    private double energyWarmup = 0.0;
    private double energyCriticalThreshold = 0.10;
    private double energyScanInterval = 1.0;
    private double lastScanUpdate = 0.0;
    private double lastEnergyUpdate = 0.0;
    private ModuleCommunicationBus comBus;
    private boolean radioOffByEnergy = false;
    private static Random energyRng = null;

    // Learning parameters
    private double discountGamma = 0.6;
    private double learningCoeff = 0.8;

    // Map status pending (dikirim ke tujuan mana saja)
    private Map<Integer, Tuple<DTNHost, List<Integer>>> waitForReward;
    private List<Connection> candidateReceiver;

    private static final String CCROUTING_NS = "CCRouting";
    private static final String UPDATE_INTERVAL = "updateInterval";
    private static final String TOTAL_STATE = "totalState";
    private static final String TOTAL_ACTION = "totalAction";

    // PRoPHET settings
    private static final String PROPHET_NS = "ProphetRouter";
    private static final String SECONDS_IN_UNIT_S = "secondsInTimeUnit";
    private static final String BETA_S = "beta";
    private static final String P_INIT_S = "pInit";
    private static final String GAMMA_S = "gamma";
    private static final double P_INIT = 0.75;
    private static final double DEFAULT_BETA = 0.25;
    private static final double GAMMA = 0.98;
    private static final String PROPHET_ENABLED = "prophetEnabled";
    private static final String PROPHET_ALLOW_NOINFO = "prophetAllowNoInfo";
    private static final String PROPHET_MIN_DELTA = "prophetMinDelta";
    private static final String BUFFER_AWARE_ENABLED = "bufferAwareEnabled";
    private static final String FUSION_ENABLED = "fusionEnabled";
    private static final String FUSION_WEIGHT_RL = "fusionWeightRL";
    private static final String FUSION_WEIGHT_PROPHET = "fusionWeightProphet";
    private static final String FUSION_WEIGHT_BUFFER = "fusionWeightBuffer";
    private static final String FUSION_WEIGHT_ENERGY = "fusionWeightEnergy";
    private static final String DISCOUNT_GAMMA_S = "discountGamma";
    private static final String LEARNING_COEFF_S = "learningCoeff";

    // Energy settings
    private static final String ENERGY_AWARE_ENABLED_S = "energyAwareEnabled";
    private static final String INITIAL_ENERGY_S = "initialEnergy";
    private static final String SCAN_ENERGY_S = "scanEnergy";
    private static final String TRANSMIT_ENERGY_S = "transmitEnergy";
    private static final String ENERGY_WARMUP_S = "energyWarmup";
    private static final String ENERGY_THRESHOLD_S = "energyCriticalThreshold";
    private static final String ENERGY_SCAN_INTERVAL_S = "energyScanInterval";

    public CCRouting(Settings s) {
        super(s);
        Settings ccSettings = new Settings(CCROUTING_NS);
        updateInterval = ccSettings.getInt(UPDATE_INTERVAL);
        totalState = ccSettings.getInt(TOTAL_STATE);
        totalAction = ccSettings.getInt(TOTAL_ACTION);

        if (ccSettings.contains(PROPHET_ENABLED)) {
            prophetEnabled = ccSettings.getBoolean(PROPHET_ENABLED);
        }
        if (ccSettings.contains(PROPHET_ALLOW_NOINFO)) {
            prophetAllowNoInfo = ccSettings.getBoolean(PROPHET_ALLOW_NOINFO);
        }
        if (ccSettings.contains(PROPHET_MIN_DELTA)) {
            prophetMinDelta = ccSettings.getDouble(PROPHET_MIN_DELTA);
        }
        if (ccSettings.contains(BUFFER_AWARE_ENABLED)) {
            bufferAwareEnabled = ccSettings.getBoolean(BUFFER_AWARE_ENABLED);
        }
        if (ccSettings.contains(DISCOUNT_GAMMA_S)) {
            discountGamma = ccSettings.getDouble(DISCOUNT_GAMMA_S);
        }
        if (ccSettings.contains(LEARNING_COEFF_S)) {
            learningCoeff = ccSettings.getDouble(LEARNING_COEFF_S);
        }
        if (ccSettings.contains(FUSION_ENABLED)) {
            fusionEnabled = ccSettings.getBoolean(FUSION_ENABLED);
        }
        if (ccSettings.contains(FUSION_WEIGHT_RL)) {
            fusionWeightRL = ccSettings.getDouble(FUSION_WEIGHT_RL);
        }
        if (ccSettings.contains(FUSION_WEIGHT_PROPHET)) {
            fusionWeightProphet = ccSettings.getDouble(FUSION_WEIGHT_PROPHET);
        }
        if (ccSettings.contains(FUSION_WEIGHT_BUFFER)) {
            fusionWeightBuffer = ccSettings.getDouble(FUSION_WEIGHT_BUFFER);
        }
        if (ccSettings.contains(FUSION_WEIGHT_ENERGY)) {
            fusionWeightEnergy = ccSettings.getDouble(FUSION_WEIGHT_ENERGY);
        }

        if (ccSettings.contains(ENERGY_AWARE_ENABLED_S)) {
            energyAwareEnabled = ccSettings.getBoolean(ENERGY_AWARE_ENABLED_S);
        }
        if (ccSettings.contains(INITIAL_ENERGY_S)) {
            initialEnergy = ccSettings.getCsvDoubles(INITIAL_ENERGY_S);
        }
        if (ccSettings.contains(SCAN_ENERGY_S)) {
            scanEnergy = ccSettings.getDouble(SCAN_ENERGY_S);
        }
        if (ccSettings.contains(TRANSMIT_ENERGY_S)) {
            transmitEnergy = ccSettings.getDouble(TRANSMIT_ENERGY_S);
        }
        if (ccSettings.contains(ENERGY_WARMUP_S)) {
            energyWarmup = ccSettings.getDouble(ENERGY_WARMUP_S);
        }
        if (ccSettings.contains(ENERGY_THRESHOLD_S)) {
            energyCriticalThreshold = ccSettings.getDouble(ENERGY_THRESHOLD_S);
        }
        if (ccSettings.contains(ENERGY_SCAN_INTERVAL_S)) {
            energyScanInterval = ccSettings.getDouble(ENERGY_SCAN_INTERVAL_S);
        }
        setEnergy(initialEnergy);

        Settings prophetSettings = new Settings(PROPHET_NS);
        if (prophetSettings.contains(SECONDS_IN_UNIT_S)) {
            secondsInTimeUnit = prophetSettings.getInt(SECONDS_IN_UNIT_S);
        } else {
            secondsInTimeUnit = 30;
        }
        if (prophetSettings.contains(BETA_S)) {
            beta = prophetSettings.getDouble(BETA_S);
        } else {
            beta = DEFAULT_BETA;
        }
        if (prophetSettings.contains(P_INIT_S)) {
            pInit = prophetSettings.getDouble(P_INIT_S);
        } else {
            pInit = P_INIT;
        }
        if (prophetSettings.contains(GAMMA_S)) {
            gamma = prophetSettings.getDouble(GAMMA_S);
        } else {
            gamma = GAMMA;
        }
        initPreds();

        waitForReward = new LinkedHashMap<>();
        candidateReceiver = new ArrayList<>();
        dataContact = new ArrayList<>();
        listOfSumDataContact = new ArrayList<>();
        initQL();
    }

    protected CCRouting(CCRouting r) {
        super(r);
        updateInterval = r.updateInterval;
        totalState = r.totalState;
        totalAction = r.totalAction;
        prophetEnabled = r.prophetEnabled;
        prophetAllowNoInfo = r.prophetAllowNoInfo;
        prophetMinDelta = r.prophetMinDelta;
        bufferAwareEnabled = r.bufferAwareEnabled;
        fusionEnabled = r.fusionEnabled;
        fusionWeightRL = r.fusionWeightRL;
        fusionWeightProphet = r.fusionWeightProphet;
        fusionWeightBuffer = r.fusionWeightBuffer;
        fusionWeightEnergy = r.fusionWeightEnergy;
        discountGamma = r.discountGamma;
        learningCoeff = r.learningCoeff;
        secondsInTimeUnit = r.secondsInTimeUnit;
        beta = r.beta;
        pInit = r.pInit;
        gamma = r.gamma;
        energyAwareEnabled = r.energyAwareEnabled;
        initialEnergy = r.initialEnergy;
        scanEnergy = r.scanEnergy;
        transmitEnergy = r.transmitEnergy;
        energyWarmup = r.energyWarmup;
        energyCriticalThreshold = r.energyCriticalThreshold;
        energyScanInterval = r.energyScanInterval;
        setEnergy(initialEnergy);
        initPreds();

        waitForReward = new LinkedHashMap<>();
        candidateReceiver = new ArrayList<>();
        dataContact = new ArrayList<>();
        listOfSumDataContact = new ArrayList<>();
        initQL();
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

    private void setEnergy(double[] range) {
        if (range == null || range.length == 0) {
            this.currentEnergy = 1000.0;
            this.initialEnergyCapacity = 1000.0;
            return;
        }
        if (range.length == 1) {
            this.currentEnergy = range[0];
            this.initialEnergyCapacity = range[0];
            return;
        }
        if (energyRng == null) {
            energyRng = new Random((int) (range[0] + range[1]));
        }
        this.currentEnergy = range[0] + energyRng.nextDouble() * (range[1] - range[0]);
        this.initialEnergyCapacity = range[1];
    }

    private void initEnergyBusIfNeeded() {
        if (!energyAwareEnabled) {
            return;
        }
        if (this.comBus == null) {
            this.comBus = getHost().getComBus();
        }

        if (this.comBus.getProperty(ENERGY_VALUE_ID) == null) {
            this.comBus.addProperty(ENERGY_VALUE_ID, this.currentEnergy);
        } else {
            this.comBus.updateProperty(ENERGY_VALUE_ID, this.currentEnergy);
        }

        if (this.comBus.getProperty(ENERGY_CAPACITY_ID) == null) {
            this.comBus.addProperty(ENERGY_CAPACITY_ID, this.initialEnergyCapacity);
        } else {
            this.comBus.updateProperty(ENERGY_CAPACITY_ID, this.initialEnergyCapacity);
        }
    }

    private void reduceEnergy(double amount) {
        if (!energyAwareEnabled || amount <= 0) {
            return;
        }
        if (SimClock.getTime() < this.energyWarmup) {
            return;
        }

        initEnergyBusIfNeeded();
        this.currentEnergy -= amount;
        if (this.currentEnergy < 0) {
            this.currentEnergy = 0;
        }
        this.comBus.updateProperty(ENERGY_VALUE_ID, this.currentEnergy);

        if (this.currentEnergy <= 0 && !this.radioOffByEnergy) {
            this.comBus.updateProperty(NetworkInterface.RANGE_ID, 0.0);
            this.radioOffByEnergy = true;
        }
    }

    private void updateEnergyConsumption() {
        if (!energyAwareEnabled) {
            return;
        }

        initEnergyBusIfNeeded();
        double simTime = SimClock.getTime();
        if (this.currentEnergy <= 0) {
            if (!this.radioOffByEnergy) {
                this.comBus.updateProperty(NetworkInterface.RANGE_ID, 0.0);
                this.radioOffByEnergy = true;
            }
            return;
        }

        if (simTime > this.lastEnergyUpdate && this.sendingConnections != null && this.sendingConnections.size() > 0) {
            reduceEnergy((simTime - this.lastEnergyUpdate) * this.transmitEnergy);
        }
        this.lastEnergyUpdate = simTime;

        if (simTime >= this.lastScanUpdate + this.energyScanInterval) {
            reduceEnergy(this.scanEnergy);
            this.lastScanUpdate = simTime;
        }
    }

    private void updateDeliveryPredFor(DTNHost host) {
        double oldValue = getPredFor(host);
        double newValue = oldValue + (1 - oldValue) * pInit;
        preds.put(host, newValue);
    }

    public double getPredFor(DTNHost host) {
        ageDeliveryPreds();
        if (preds.containsKey(host)) {
            return preds.get(host);
        } else {
            return 0;
        }
    }

    protected Map<DTNHost, Double> getDeliveryPreds() {
        ageDeliveryPreds();
        return this.preds;
    }

    private void ageDeliveryPreds() {
        if (secondsInTimeUnit <= 0) {
            return;
        }
        double timeDiff = (SimClock.getTime() - this.lastAgeUpdate) / secondsInTimeUnit;

        if (timeDiff == 0) {
            return;
        }

        double mult = Math.pow(gamma, timeDiff);
        for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
            e.setValue(e.getValue() * mult);
        }
        this.lastAgeUpdate = SimClock.getTime();
    }

    private void updateTransitivePreds(DTNHost host) {
        MessageRouter otherRouter = host.getRouter();
        if (!(otherRouter instanceof CCRouting)) {
            return;
        }

        double pForHost = getPredFor(host);
        Map<DTNHost, Double> othersPreds = ((CCRouting) otherRouter).getDeliveryPreds();

        for (Map.Entry<DTNHost, Double> e : othersPreds.entrySet()) {
            if (e.getKey() == getHost()) {
                continue;
            }
            double pOld = getPredFor(e.getKey());
            double pNew = pOld + (1 - pOld) * pForHost * e.getValue() * beta;
            preds.put(e.getKey(), pNew);
        }
    }

    private boolean shouldForwardByBufferFactor(Message m, DTNHost other) {
        if (!bufferAwareEnabled) {
            return true;
        }
        MessageRouter router = other.getRouter();
        int free = router.getFreeBufferSize();
        if (free == Integer.MAX_VALUE) {
            return true;
        }
        return free >= m.getSize();
    }

    private double getBufferFactor(DTNHost host) {
        MessageRouter router = host.getRouter();
        int cInit = router.getBufferSize();
        if (cInit == Integer.MAX_VALUE || cInit <= 0) {
            return 1.0;
        }

        Map<Integer, Integer> counts = new HashMap<>();
        for (Message m : router.getMessageCollection()) {
            int size = m.getSize();
            counts.put(size, counts.getOrDefault(size, 0) + 1);
        }
        long sum = 0;
        for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
            sum += (long) e.getKey() * (long) e.getValue();
        }
        double bf = 1.0 - ((double) sum / (double) cInit);
        if (bf < 0) {
            bf = 0;
        }
        if (bf > 1) {
            bf = 1;
        }
        return bf;
    }

    private double getEnergyFactorFromHost(DTNHost host) {
        Object currentObj = host.getComBus().getProperty(ENERGY_VALUE_ID);
        Object capObj = host.getComBus().getProperty(ENERGY_CAPACITY_ID);
        if (!(currentObj instanceof Double) || !(capObj instanceof Double)) {
            return 1.0;
        }
        double cur = (Double) currentObj;
        double cap = (Double) capObj;
        if (cap <= 0) {
            return 0.0;
        }
        double ef = cur / cap;
        if (ef < 0) {
            ef = 0;
        }
        if (ef > 1) {
            ef = 1;
        }
        return ef;
    }

    private double getEnergyFactor(DTNHost host) {
        if (host == getHost()) {
            return getSelfEnergyFactor();
        }
        return getEnergyFactorFromHost(host);
    }

    public double getSelfEnergyFactor() {
        if (!energyAwareEnabled || initialEnergyCapacity <= 0) {
            return 1.0;
        }
        double ef = currentEnergy / initialEnergyCapacity;
        if (ef < 0) {
            ef = 0;
        }
        if (ef > 1) {
            ef = 1;
        }
        return ef;
    }

    public boolean isDeadNode() {
        return energyAwareEnabled && currentEnergy <= 0;
    }

    private boolean shouldForwardByEnergy(Message m, DTNHost other) {
        if (!energyAwareEnabled) {
            return true;
        }
        if (m.getTo() == other) {
            return true;
        }
        return getEnergyFactor(other) > energyCriticalThreshold;
    }

    private double getFusionScore(Message m, DTNHost other) {
        if (!fusionEnabled) {
            return sumList(countInterestSimilarity(m, other));
        }
        List<Double> sims = countInterestSimilarity(m, other);
        double rlScore = 0.0;
        if (!sims.isEmpty()) {
            rlScore = sumList(sims) / sims.size();
        }
        double prophetDelta = getOtherPredFor(m, other) - getPredFor(m.getTo());
        if (prophetDelta < 0) {
            prophetDelta = 0;
        }
        double bf = bufferAwareEnabled ? getBufferFactor(other) : 1.0;
        double ef = energyAwareEnabled ? getEnergyFactor(other) : 1.0;
        return (fusionWeightRL * rlScore)
                + (fusionWeightProphet * prophetDelta)
                + (fusionWeightBuffer * bf)
                + (fusionWeightEnergy * ef);
    }

    private boolean shouldForwardByProphet(Message m, DTNHost other) {
        if (!prophetEnabled) {
            return true;
        }
        if (m.getTo() == other) {
            return true;
        }

        double otherPred = getOtherPredFor(m, other);
        double myPred = getPredFor(m.getTo());

        if (otherPred == 0 && myPred == 0) {
            return prophetAllowNoInfo;
        }
        return (otherPred - myPred) > prophetMinDelta;
    }

    private double getOtherPredFor(Message m, DTNHost other) {
        if (!prophetEnabled) {
            return 0;
        }
        MessageRouter otherRouter = other.getRouter();
        if (otherRouter instanceof CCRouting) {
            return ((CCRouting) otherRouter).getPredFor(m.getTo());
        }
        return 0;
    }

    @Override
    protected int checkReceiving(Message m) {
        if (isDeadNode()) {
            return DENIED_UNSPECIFIED;
        }
        return super.checkReceiving(m);
    }

    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);
        DTNHost myHost = getHost();
        DTNHost otherNode = con.getOtherNode(myHost);

        if (con.isUp()) {
            if (!this.waitForReward.containsKey(otherNode.getAddress())) {
                this.waitForReward.put(otherNode.getAddress(), new Tuple<>(otherNode, new ArrayList<>()));
            }

            if (this.waitForReward.get(otherNode.getAddress()).getValue().isEmpty()) {
                this.candidateReceiver.add(con);
            }

            if (prophetEnabled) {
                updateDeliveryPredFor(otherNode);
                updateTransitivePreds(otherNode);
            }
        } else {
            this.totalContactTime += SimClock.getTime();
        }
    }

    @Override
    public Message messageTransferred(String id, DTNHost from) {
        Message m = super.messageTransferred(id, from);
        this.msgReceived++;
        this.dataReceived += m.getSize();
        return m;
    }

    @Override
    protected void transferDone(Connection con) {
        this.msgTransferred++;
        this.dataTransferred += con.getMessage().getSize();
    }

    @Override
    public void update() {
        super.update();
        updateEnergyConsumption();

        if (isDeadNode()) {
            return;
        }

        if (isTransferring() || !canStartTransfer()) {
            return;
        }

        if (exchangeDeliverableMessages() != null) {
            return;
        }

        tryOtherMessage();

        if ((SimClock.getTime() - lastUpdateTime) >= updateInterval) {
            lastUpdateTime = SimClock.getTime();

            for (Map.Entry<Integer, Tuple<DTNHost, List<Integer>>> entry : waitForReward.entrySet()) {
                if (entry.getValue() == null || entry.getValue().getValue() == null || entry.getValue().getValue().isEmpty()) {
                    continue;
                }

                DTNHost other = entry.getValue().getKey();
                List<Integer> destinationsSent = entry.getValue().getValue();
                CCRouting othRouter = (CCRouting) other.getRouter();

                int totalVisit = visitCount.get(other) != null ? visitCount.get(other) + 1 : 1;
                this.visitCount.put(other, totalVisit);

                double encounterProb = prophetEnabled ? getPredFor(other) : 1.0;
                if (encounterProb < 0) {
                    encounterProb = 0;
                }
                if (encounterProb > 1) {
                    encounterProb = 1;
                }

                double bf = bufferAwareEnabled ? getBufferFactor(other) : 1.0;
                double ef = energyAwareEnabled ? getEnergyFactor(other) : 1.0;

                this.ql.setLearningRate(totalVisit, learningCoeff);
                this.ql.setDiscountFactorDynamic(discountGamma, bf, ef);

                int previousState = getHost().getAddress();
                int action = other.getAddress();
                int nextState = other.getAddress();

                for (int destAddress : destinationsSent) {
                    this.ql.UpdateState(destAddress, previousState, action, encounterProb, nextState, this, other);
                }

                othRouter.dataReceived = 0;
                othRouter.dataTransferred = 0;
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
            CCRouting othRouter = (CCRouting) other.getRouter();

            if (othRouter.isTransferring()) {
                continue;
            }

            List<Tuple<Message, Connection>> tempMessages = new ArrayList<>();
            for (Message m : msgCollection) {
                if (othRouter.hasMessage(m.getId())) {
                    continue;
                }
                if (!shouldForwardByBufferFactor(m, other)) {
                    continue;
                }
                if (!shouldForwardByProphet(m, other)) {
                    continue;
                }
                if (!shouldForwardByEnergy(m, other)) {
                    continue;
                }

                int destinationAddress = m.getTo().getAddress();
                boolean approvedByQ;
                if (m.getTo() == other) {
                    approvedByQ = true;
                } else {
                    int currentState = getHost().getAddress();
                    double qForward = this.ql.getQV(destinationAddress, currentState, other.getAddress());
                    double qCarry = this.ql.getQV(destinationAddress, currentState, getHost().getAddress());
                    approvedByQ = qForward > qCarry;
                }

                if (approvedByQ) {
                    newState = other.getAddress();
                    tempMessages.add(new Tuple<>(m, con));
                }
            }

            if (!tempMessages.isEmpty()) {
                Collections.sort(tempMessages, new InteresetSimilarityComparator());

                List<Integer> sentDestinations = new ArrayList<>();
                for (Tuple<Message, Connection> t : tempMessages) {
                    sentDestinations.add(t.getKey().getTo().getAddress());
                }

                messages.addAll(tempMessages);
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

    private class InteresetSimilarityComparator implements Comparator<Tuple<Message, Connection>> {
        public int compare(Tuple<Message, Connection> tuple1, Tuple<Message, Connection> tuple2) {
            Message m1 = tuple1.getKey();
            Message m2 = tuple2.getKey();
            DTNHost h1 = tuple1.getValue().getOtherNode(getHost());
            DTNHost h2 = tuple2.getValue().getOtherNode(getHost());
            double s1 = getFusionScore(m1, h1);
            double s2 = getFusionScore(m2, h2);
            int cmp = Double.compare(s2, s1);
            if (cmp != 0) {
                return cmp;
            }
            return compareByQueueMode(m1, m2);
        }
    }

    @Override
    public CCRouting replicate() {
        return new CCRouting(this);
    }

    public int getTotalDataRcv() {
        return this.dataReceived;
    }

    public int getTotalDataTrf() {
        return this.dataTransferred;
    }

    public int getMsgReceived() {
        return this.msgReceived;
    }

    public int getMsgTransferred() {
        return this.msgTransferred;
    }

    public double getCr() {
        return this.cr;
    }

    public double getEma() {
        return this.ema;
    }

    public double getCurrentEnergy() {
        return this.currentEnergy;
    }

    public double getInitialEnergyCapacity() {
        return this.initialEnergyCapacity;
    }

    public void countCongestionRatio() {
        if (totalContactTime <= 0) {
            return;
        }
        double dataEachContact = (this.msgReceived + this.msgTransferred) / totalContactTime;
        this.dataContact.add(dataEachContact);
        double summedData = sumList(this.dataContact);
        this.listOfSumDataContact.add(summedData);
        this.cr = avgList(this.listOfSumDataContact);
    }

    public void countEma(double oLast) {
        double emaPrev = this.ema;
        double tempEma = oLast * SMOOTHING_FACTOR + emaPrev * (1 - SMOOTHING_FACTOR);
        this.ema = tempEma;
    }

    private double sumList(List<Double> lists) {
        double total = 0.0;
        for (double lst : lists) {
            total += lst;
        }
        return total;
    }

    private double avgList(List<Double> lists) {
        if (lists.isEmpty()) {
            return 0;
        }
        double value = 0;
        for (double i : lists) {
            value += i;
        }
        return value / lists.size();
    }

    public QLearning getQl() {
        return this.ql;
    }

    public void setDataReceiveTransmit(int value) {
        this.dataReceived = value;
        this.dataTransferred = value;
        this.msgReceived = value;
        this.msgTransferred = value;
    }

    @Override
    public Map<Integer, Tuple<DTNHost, List<Integer>>> getMapWaitForReward() {
        return this.waitForReward;
    }
}
