package routing;

import core.*;
import java.util.*;
import reinforcement.*;
import routing.community.Duration;

public class CCRouting extends QLearningRouter {

    private double updateInterval;

    // Variable untuk Congestion Ratio (Metrik, tidak digunakan langsung di Q-Learning)
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
    private int totalState = 0;
    private int totalAction = 0;
    private Map<Integer, Integer> visitCount;

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
    private double bufferFactorMin = 0.0;
    private boolean fusionEnabled = false;
    private double fusionWeightRL = 0.0;
    private double fusionWeightProphet = 0.0;
    private double fusionWeightBuffer = 0.0;
    private double fusionWeightEnergy = 0.0;

    // Energy awareness
    private boolean energyAwareEnabled = false;
    private boolean energyAffectsLearning = true;
    private double energyThreshold = 0.10;
    private double energyInit = 0.0;
    private double energyInitDelta = 0.0;
    private double scanEnergy = 0.0;
    private double transmitEnergy = 0.0;
    private double warmupTime = 0.0;
    private double scanInterval = 0.0;
    private double energyCapacity = 0.0;
    private double currentEnergy = 0.0;
    private double lastEnergyUpdate = 0.0;
    private double lastScanUpdate = 0.0;
    private ModuleCommunicationBus comBus = null;
    private static Random energyRng = null;
    private static final String ENERGY_CAPACITY_ID = "Energy.capacity";

    // Learning parameters
    private double discountGamma = 0.6;
    private double learningCoeff = 0.8;

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
    private static final String BUFFER_FACTOR_MIN = "bufferFactorMin";
    private static final String FUSION_ENABLED = "fusionEnabled";
    private static final String FUSION_WEIGHT_RL = "fusionWeightRL";
    private static final String FUSION_WEIGHT_PROPHET = "fusionWeightProphet";
    private static final String FUSION_WEIGHT_BUFFER = "fusionWeightBuffer";
    private static final String FUSION_WEIGHT_ENERGY = "fusionWeightEnergy";
    private static final String DISCOUNT_GAMMA_S = "discountGamma";
    private static final String LEARNING_COEFF_S = "learningCoeff";
    private static final String ENERGY_AWARE_ENABLED = "energyAwareEnabled";
    private static final String ENERGY_AFFECTS_LEARNING = "energyAffectsLearning";
    private static final String ENERGY_THRESHOLD = "energyThreshold";
    private static final String ENERGY_INIT = "energyInit";
    private static final String ENERGY_INIT_DELTA = "energyInitDelta";
    private static final String ENERGY_SCAN = "scanEnergy";
    private static final String ENERGY_TRANSMIT = "transmitEnergy";
    private static final String ENERGY_WARMUP = "energyWarmup";

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
        if (ccSettings.contains(BUFFER_FACTOR_MIN)) {
            bufferFactorMin = ccSettings.getDouble(BUFFER_FACTOR_MIN);
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
        if (ccSettings.contains(ENERGY_AWARE_ENABLED)) {
            energyAwareEnabled = ccSettings.getBoolean(ENERGY_AWARE_ENABLED);
        }
        if (ccSettings.contains(ENERGY_AFFECTS_LEARNING)) {
            energyAffectsLearning = ccSettings.getBoolean(ENERGY_AFFECTS_LEARNING);
        }
        if (ccSettings.contains(ENERGY_THRESHOLD)) {
            energyThreshold = ccSettings.getDouble(ENERGY_THRESHOLD);
        }
        if (ccSettings.contains(ENERGY_INIT)) {
            energyInit = ccSettings.getDouble(ENERGY_INIT);
        }
        if (ccSettings.contains(ENERGY_INIT_DELTA)) {
            energyInitDelta = ccSettings.getDouble(ENERGY_INIT_DELTA);
        }
        if (ccSettings.contains(ENERGY_SCAN)) {
            scanEnergy = ccSettings.getDouble(ENERGY_SCAN);
        }
        if (ccSettings.contains(ENERGY_TRANSMIT)) {
            transmitEnergy = ccSettings.getDouble(ENERGY_TRANSMIT);
        }
        if (ccSettings.contains(ENERGY_WARMUP)) {
            warmupTime = ccSettings.getDouble(ENERGY_WARMUP);
        }
        if (s.contains(SimScenario.SCAN_INTERVAL_S)) {
            scanInterval = s.getDouble(SimScenario.SCAN_INTERVAL_S);
        } else {
            scanInterval = 0.0;
        }

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

        // Initialize maps and lists
        this.preds = new HashMap<>();
        this.visitCount = new HashMap<>();
        this.waitForReward = new HashMap<>();
        this.candidateReceiver = new ArrayList<>();
        this.dataContact = new ArrayList<>();
        this.listOfSumDataContact = new ArrayList<>();

        initEnergy();
        initPreds();
        initQL();
    }

    protected CCRouting(CCRouting r) {
        super(r);
        this.updateInterval = r.updateInterval;
        this.totalState = r.totalState;
        this.totalAction = r.totalAction;
        this.secondsInTimeUnit = r.secondsInTimeUnit;
        this.beta = r.beta;
        this.pInit = r.pInit;
        this.gamma = r.gamma;
        this.prophetEnabled = r.prophetEnabled;
        this.prophetAllowNoInfo = r.prophetAllowNoInfo;
        this.prophetMinDelta = r.prophetMinDelta;
        this.bufferAwareEnabled = r.bufferAwareEnabled;
        this.bufferFactorMin = r.bufferFactorMin;
        this.discountGamma = r.discountGamma;
        this.learningCoeff = r.learningCoeff;
        this.fusionEnabled = r.fusionEnabled;
        this.fusionWeightRL = r.fusionWeightRL;
        this.fusionWeightProphet = r.fusionWeightProphet;
        this.fusionWeightBuffer = r.fusionWeightBuffer;
        this.fusionWeightEnergy = r.fusionWeightEnergy;
        this.energyAwareEnabled = r.energyAwareEnabled;
        this.energyAffectsLearning = r.energyAffectsLearning;
        this.energyThreshold = r.energyThreshold;
        this.energyInit = r.energyInit;
        this.energyInitDelta = r.energyInitDelta;
        this.scanEnergy = r.scanEnergy;
        this.transmitEnergy = r.transmitEnergy;
        this.warmupTime = r.warmupTime;
        this.scanInterval = r.scanInterval;

        // Safely clone current state from the prototype
        this.preds = r.preds != null ? new HashMap<>(r.preds) : new HashMap<>();
        this.visitCount = r.visitCount != null ? new HashMap<>(r.visitCount) : new HashMap<>();
        this.waitForReward = r.waitForReward != null ? new HashMap<>(r.waitForReward) : new HashMap<>();
        this.candidateReceiver = r.candidateReceiver != null ? new ArrayList<>(r.candidateReceiver) : new ArrayList<>();
        this.dataContact = r.dataContact != null ? new ArrayList<>(r.dataContact) : new ArrayList<>();
        this.listOfSumDataContact = r.listOfSumDataContact != null ? new ArrayList<>(r.listOfSumDataContact) : new ArrayList<>();
        initEnergy();
        initQL();
    }

    @Override
    public CCRouting replicate() {
        return new CCRouting(this);
    }

    protected void initQL() {
        this.explorationPolicy = new EpsilonGreedyExploration(0.989);
        this.ql = new QLearning(totalState, totalAction, this.explorationPolicy, false);
    }

    private void initPreds() {
        if (this.preds == null) this.preds = new HashMap<>();
    }

    private void initEnergy() {
        if (energyAwareEnabled) {
            energyCapacity = energyInit;
            if (energyInitDelta > 0 && energyRng != null) {
                energyCapacity += energyRng.nextDouble() * energyInitDelta;
            }
            currentEnergy = energyCapacity;
        }
    }

    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);
        if (con.isUp()) {
            candidateReceiver.add(con);
            DTNHost other = con.getOtherNode(getHost());
            updateDeliveryPredictability(other);
        } else {
            candidateReceiver.remove(con);
        }
    }

    private void updateDeliveryPredictability(DTNHost host) {
        double amt = getPredFor(host);
        preds.put(host, amt + (1 - amt) * pInit);
        // Transitive property
        for (Map.Entry<DTNHost, Double> entry : ((CCRouting) host.getRouter()).preds.entrySet()) {
            DTNHost otherHost = entry.getKey();
            if (otherHost == getHost()) continue;
            double otherP = entry.getValue();
            double myP = getPredFor(otherHost);
            preds.put(otherHost, myP + (1 - myP) * amt * otherP * beta);
        }
    }

    private double getPredFor(DTNHost host) {
        ageDeliveryPredictability();
        return preds.containsKey(host) ? preds.get(host) : 0;
    }

    private void ageDeliveryPredictability() {
        double time = SimClock.getTime();
        double delta = time - lastAgeUpdate;
        if (delta <= 0) return;
        double units = delta / secondsInTimeUnit;
        double factor = Math.pow(gamma, units);
        for (Map.Entry<DTNHost, Double> entry : preds.entrySet()) {
            entry.setValue(entry.getValue() * factor);
        }
        lastAgeUpdate = time;
    }

    @Override
    public void update() {
        super.update();
        if (isTransferring() || !canStartTransfer()) return;
        if (exchangeDeliverableMessages() != null) return;
        tryAllMessagesToAllConnections();

        // RL Training Cycle
        double currentTime = SimClock.getTime();
        if (currentTime - lastUpdateTime >= updateInterval) {
            processRewards();
            lastUpdateTime = currentTime;
        }
    }

    private void processRewards() {
        for (Integer msgId : new ArrayList<>(waitForReward.keySet())) {
            Tuple<DTNHost, List<Integer>> tuple = waitForReward.get(msgId);
            DTNHost other = tuple.getKey();
            List<Integer> nextStates = tuple.getValue();

            // Training logic
            int myAddress = getHost().getAddress();
            int otherAddress = other.getAddress();

            int totalVisit = visitCount.containsKey(otherAddress) ? visitCount.get(otherAddress) + 1 : 1;
            visitCount.put(otherAddress, totalVisit);

            double bf = bufferAwareEnabled ? getBufferFactor(other) : 1.0;
            double ef = energyAffectsLearning ? getEnergyLearningFactor() : 1.0;
            ql.setDiscountFactorDynamic(discountGamma, bf, ef);
            ql.setLearningRate(totalVisit, learningCoeff);

            for (Integer destAddress : nextStates) {
                double reward = calculateReward(destAddress, other);
                int action = otherAddress; // Action is moving to 'other'
                int nextState = otherAddress; // Simplification for ORQLCI
                ql.UpdateState(destAddress, myAddress, action, reward, nextState, this, other);
            }
            waitForReward.remove(msgId);
        }
    }

    private double calculateReward(int destAddr, DTNHost other) {
        // Simplified reward based on delivery predictability + distance
        double p = getPredFor(other);
        return p; 
    }

    private double getBufferFactor(DTNHost other) {
        double occupancy = other.getBufferOccupancy() / 100.0;
        return Math.max(bufferFactorMin, 1.0 - occupancy);
    }

    private double getEnergyLearningFactor() {
        if (!energyAwareEnabled) return 1.0;
        double ratio = currentEnergy / energyCapacity;
        return Math.max(0.1, ratio); // Floor at 0.1
    }

    @Override
    protected Connection tryAllMessagesToAllConnections() {
        List<Message> msgs = new ArrayList<>(getMessageCollection());
        // Collections.sort(msgs, new MessageComparator()); // Message has no getPriority in this generic ONE
        List<Connection> connections = new ArrayList<>(candidateReceiver);
        Connection finalBestCon = null;

        for (Message m : msgs) {
            Connection bestCon = null;
            double bestScore = -1;

            for (Connection con : connections) {
                DTNHost other = con.getOtherNode(getHost());
                DTNHost lastHop = m.getHops().size() > 0 ? m.getHops().get(m.getHops().size() - 1) : m.getFrom();
                if (m.getHopCount() > 0 && lastHop == other) continue;

                double score = getFusionScore(m, other);
                if (score > bestScore) {
                    bestScore = score;
                    bestCon = con;
                    finalBestCon = con;
                }
            }

            if (bestCon != null && bestScore > getFusionScore(m, getHost())) {
                if (bestCon.isReadyForTransfer()) {
                    if (startTransfer(m, bestCon) == RCV_OK) {
                        // Mark for reward tracking
                        int destAddr = m.getTo().getAddress();
                        List<Integer> dlist = new ArrayList<>();
                        dlist.add(destAddr);
                        waitForReward.put(m.hashCode(), new Tuple<>(bestCon.getOtherNode(getHost()), dlist));
                        break;
                    }
                }
            }
        }
        return finalBestCon;
    }

    private double getFusionScore(Message m, DTNHost other) {
        if (!fusionEnabled) {
            List<Double> sims = countInterestSimilarity(m, other);
            return sumList(sims);
        }

        int destAddr = m.getTo().getAddress();
        int myAddr = other.getAddress();
        
        // RL Score
        int action = myAddr; // best action estimate
        double rlScore = ql.getQV(destAddr, myAddr, action);

        // Prophet Score
        double pScore = getPredFor(other);

        // Buffer Score
        double bScore = 1.0 - (other.getBufferOccupancy() / 100.0);

        // Energy Score
        double eScore = energyAwareEnabled ? (currentEnergy / energyCapacity) : 1.0;

        return (fusionWeightRL * rlScore) + 
               (fusionWeightProphet * pScore) + 
               (fusionWeightBuffer * bScore) + 
               (fusionWeightEnergy * eScore);
    }

    private double sumList(List<Double> list) {
        double sum = 0;
        for (Double d : list) sum += d;
        return sum;
    }

    @Override
    public Map<Integer, Tuple<DTNHost, List<Integer>>> getMapWaitForReward() {
        return waitForReward;
    }

    public double getCr() {
        return cr;
    }

    public double getEma() {
        return ema;
    }
}
