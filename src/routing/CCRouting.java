package routing;

import core.*;
import java.util.*;
import reinforcement.*;

public class CCRouting extends QLearningRouter {

    private double updateInterval;

    // Metrik internal
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

    // Q-Learning state
    private QLearning ql;
    private IExplorationPolicy explorationPolicy;
    private int totalState;
    private int totalAction;
    private Map<DTNHost, Integer> visitCount;
    private int newState = 0;

    // Toggles (Disesuaikan dengan Config)
    private boolean prophetEnabled = true;
    private boolean prophetAllowNoInfo = true;
    private boolean bufferAwareEnabled = true;
    private boolean fusionEnabled = true;

    // PRoPHET state
    private int secondsInTimeUnit;
    private double beta;
    private double lastAgeUpdate = 0.0;
    private Map<DTNHost, Double> preds;
    private double pInit = P_INIT;
    private double gamma = GAMMA;
    private double prophetMinDelta = 0.0;
    
    // Weights & Learning Params
    private double fusionWeightRL = 0.7;
    private double fusionWeightProphet = 0.2;
    private double fusionWeightBuffer = 0.1;
    private double discountGamma = 0.6;
    private double learningCoeff = 0.8;

    private Map<Integer, Tuple<DTNHost, List<Integer>>> waitForReward;
    private List<Connection> candidateReceiver;

    private static final String CCROUTING_NS = "CCRouting";
    private static final String UPDATE_INTERVAL = "updateInterval";
    private static final String TOTAL_STATE = "totalState";
    private static final String TOTAL_ACTION = "totalAction";
    private static final String PROPHET_NS = "ProphetRouter";
    private static final double P_INIT = 0.75;
    private static final double DEFAULT_BETA = 0.25;
    private static final double GAMMA = 0.98;

    public CCRouting(Settings s) {
        super(s);
        Settings ccSettings = new Settings(CCROUTING_NS);
        updateInterval = ccSettings.getInt(UPDATE_INTERVAL);
        totalState = ccSettings.getInt(TOTAL_STATE);
        totalAction = ccSettings.getInt(TOTAL_ACTION);

        // Membaca Toggle dari Config
        if (ccSettings.contains("prophetEnabled")) prophetEnabled = ccSettings.getBoolean("prophetEnabled");
        if (ccSettings.contains("bufferAwareEnabled")) bufferAwareEnabled = ccSettings.getBoolean("bufferAwareEnabled");
        if (ccSettings.contains("fusionEnabled")) fusionEnabled = ccSettings.getBoolean("fusionEnabled");
        
        // Membaca Weights
        if (ccSettings.contains("fusionWeightRL")) fusionWeightRL = ccSettings.getDouble("fusionWeightRL");
        if (ccSettings.contains("fusionWeightProphet")) fusionWeightProphet = ccSettings.getDouble("fusionWeightProphet");
        if (ccSettings.contains("fusionWeightBuffer")) fusionWeightBuffer = ccSettings.getDouble("fusionWeightBuffer");

        Settings pSet = new Settings(PROPHET_NS);
        secondsInTimeUnit = pSet.contains("secondsInTimeUnit") ? pSet.getInt("secondsInTimeUnit") : 30;
        beta = pSet.contains("beta") ? pSet.getDouble("beta") : DEFAULT_BETA;
        pInit = pSet.contains("pInit") ? pSet.getDouble("pInit") : P_INIT;
        gamma = pSet.contains("gamma") ? pSet.getDouble("gamma") : GAMMA;

        initPreds();
        waitForReward = new LinkedHashMap<>();
        candidateReceiver = new ArrayList<>();
        dataContact = new ArrayList<>();
        listOfSumDataContact = new ArrayList<>();
        initQL();
    }

    protected CCRouting(CCRouting r) {
        super(r);
        this.updateInterval = r.updateInterval;
        this.totalState = r.totalState;
        this.totalAction = r.totalAction;
        this.prophetEnabled = r.prophetEnabled;
        this.bufferAwareEnabled = r.bufferAwareEnabled;
        this.fusionEnabled = r.fusionEnabled;
        this.fusionWeightRL = r.fusionWeightRL;
        this.fusionWeightProphet = r.fusionWeightProphet;
        this.fusionWeightBuffer = r.fusionWeightBuffer;
        this.discountGamma = r.discountGamma;
        this.learningCoeff = r.learningCoeff;
        this.secondsInTimeUnit = r.secondsInTimeUnit;
        this.beta = r.beta;
        this.pInit = r.pInit;
        this.gamma = r.gamma;

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
        this.totalRewardWithNode = new LinkedHashMap<>();
        this.visitCount = new LinkedHashMap<>();
    }

    private void initPreds() {
        this.preds = new LinkedHashMap<>();
        this.lastAgeUpdate = 0.0;
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
        if (bf < 0) bf = 0;
        if (bf > 1) bf = 1;
        return bf;
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
        if (prophetDelta < 0) prophetDelta = 0;
        double bf = bufferAwareEnabled ? getBufferFactor(other) : 1.0;
        return (fusionWeightRL * rlScore) + (fusionWeightProphet * prophetDelta) + (fusionWeightBuffer * bf);
    }

    private boolean shouldForwardByProphet(Message m, DTNHost other) {
        if (!prophetEnabled) return true;
        if (m.getTo() == other) return true;

        double otherPred = getOtherPredFor(m, other);
        double myPred = getPredFor(m.getTo());

        if (otherPred == 0 && myPred == 0) return prophetAllowNoInfo;
        return (otherPred - myPred) > prophetMinDelta;
    }

    private double getOtherPredFor(Message m, DTNHost other) {
        if (!prophetEnabled) return 0;
        MessageRouter otherRouter = other.getRouter();
        if (otherRouter instanceof CCRouting) {
            return ((CCRouting) otherRouter).getPredFor(m.getTo());
        }
        return 0;
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
        // WAJIB: Memanggil super agar internal state simulator tetap bersih
        super.transferDone(con);
        
        this.msgTransferred++;
        this.dataTransferred += con.getMessage().getSize();

        // OPTIMASI: Jika kita berhasil mengirim pesan ke TUJUAN AKHIRNYA, 
        // hapus pesan tersebut dari buffer kita (menghemat RAM & mematikan Ping-Pong)
        Message m = con.getMessage();
        if (m.getTo() == con.getOtherNode(getHost())) {
            this.deleteMessage(m.getId(), false);
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

        tryOtherMessage();

        if ((SimClock.getTime() - lastUpdateTime) >= updateInterval) {

            lastUpdateTime = SimClock.getTime();

            for (Map.Entry<Integer, Tuple<DTNHost, List<Integer>>> entry : waitForReward.entrySet()) {
                if (entry.getKey() == newState && entry.getValue().getValue() != null && !entry.getValue().getValue().isEmpty()) {
                    DTNHost other = entry.getValue().getKey();
                    List<Integer> destinationsSent = entry.getValue().getValue();
                    CCRouting othRouter = (CCRouting) other.getRouter();

                    // === START UPDATE SESUAI ORQLCI ===
                    int totalVisit = visitCount.get(other) != null ? visitCount.get(other) + 1 : 1;
                    this.visitCount.put(other, totalVisit);

                    // Ambil Encounter Probability & Buffer Factor
                    double encounterProb = getPredFor(other); // (EP_x)
                    double bf = bufferAwareEnabled ? getBufferFactor(other) : 1.0; // (BF_x)

                    for (int destAddress : destinationsSent) { 
                        // REWARD ORQLCI: 1 jika sampai di tujuan akhir, 0 jika ke perantara
                        double reward = (other.getAddress() == destAddress) ? 1.0 : 0.0;
                        
                        int action = this.ql.GetAction(destAddress, entry.getKey(), this.waitForReward, true);
                        this.ql.setLearningRate(totalVisit, learningCoeff);

                        // Discount factor dihitung sesuai Persamaan (7) di paper
                        this.ql.setDiscountFactorDynamic(discountGamma, bf);
                        this.ql.UpdateState(destAddress, entry.getKey(), action, reward, newState, this, other);
                    }
                    // === END UPDATE SESUAI ORQLCI ===

                    othRouter.dataReceived = 0;
                    othRouter.dataTransferred = 0;
                    othRouter.msgReceived = 0;
                    othRouter.msgTransferred = 0;
                }
            }
        }
    }

    @Override
    public boolean isFinalDest(Message m) {
        return m.getTo() == getHost();
    }

    private Tuple<Message, Connection> tryOtherMessage() {
        List<Tuple<Message, Connection>> messages = new ArrayList<>();
        List<Tuple<Message, Connection>> tempMessages = new ArrayList<>();
        Collection<Message> msgCollection = getMessageCollection();

        Iterator<Connection> it = candidateReceiver.iterator();
        while (it.hasNext()) {
            Connection con = it.next();
            DTNHost other = con.getOtherNode(getHost());
            CCRouting othRouter = (CCRouting) other.getRouter();

            if (othRouter.isTransferring()) continue;

            for (Message m : msgCollection) {
                if (othRouter.hasMessage(m.getId())) continue; 
                if (!shouldForwardByBufferFactor(m, other)) continue;
                
                // Jangan forward pesan yang tujuan akhirnya adalah SAYA (sudah sampai rumah)
                if (m.getTo() == getHost()) continue;

                int destinationAddress = m.getTo().getAddress();
                newState = this.ql.GetAction(destinationAddress, other.getAddress(), this.waitForReward, false);

                if (newState == other.getAddress()) {
                    tempMessages.add(new Tuple<>(m, con));
                }
            }

            if (!tempMessages.isEmpty()) {
                Collections.sort(tempMessages, new InteresetSimilarityComparator());
                messages.addAll(tempMessages);

                // --- PERBAIKAN URUTAN DI SINI ---
                List<Integer> sentDestinations = new ArrayList<>();
                for (Tuple<Message, Connection> t : tempMessages) {
                    sentDestinations.add(t.getKey().getTo().getAddress());
                }
                
                // BARU DI-CLEAR setelah data diambil
                tempMessages.clear(); 
                
                this.waitForReward.put(other.getAddress(), new Tuple<>(other, sentDestinations));
                it.remove();
                it = candidateReceiver.iterator();
            }
        }

        if (messages.isEmpty()) return null;
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

    public int getTotalDataRcv() { return this.dataReceived; }
    public int getTotalDataTrf() { return this.dataTransferred; }
    public int getMsgReceived() { return this.msgReceived; }
    public int getMsgTransferred() { return this.msgTransferred; }
    public double getCr() { return this.cr; }
    public double getEma() { return this.ema; }

    public void countCongestionRatio() {
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
        for (double lst : lists) total += lst;
        return total;
    }

    private double avgList(List<Double> lists) {
        if (lists.isEmpty()) return 0;
        double value = 0;
        for (double i : lists) value += i;
        return value / lists.size();
    }

    public QLearning getQl() { return this.ql; }

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