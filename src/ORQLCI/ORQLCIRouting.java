package ORQLCI;

import core.*;
import java.util.*;

// public class CCRouting extends ActiveRouter {
public class ORQLCIRouting extends QLearningRouter {

	/** update interval diset dari settings */
	private double updateInterval;

	// Variable untuk Congestion Ratio
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
	/** untuk menghitung discount factor */
	private Map<DTNHost, Double> totalRewardWithNode;
	private Map<DTNHost, Integer> visitCount;
	private int newState = 0;

	/** 
	 * Integer sebagai address node, 
	 * jika status masih <CODE>pending</CODE> 
	 * atau <CODE>true</CODE> maka tidak dikirim
	 * */
	private Map<Integer, Tuple<DTNHost, Boolean>> waitForReward;

	/**
	 * Candidate receivers
	 */
	private List<Connection> candidateReceiver;

	/** namespace settings ({@value}) */
	private static final String CCROUTING_NS = "CCRouting";
	/** nilai update interval atur di settings */
	private static final String UPDATE_INTERVAL = "updateInterval";
	/**
	 * Karena node dianggap sebagai state,
	 * maka state diinisalisasi sejumlah dengan total node
	 */
	private static final String TOTAL_STATE = "totalState";
	private static final String TOTAL_ACTION = "totalAction";

	/**
	 * Constructor
	 * 
	 * @param s
	 */
	public ORQLCIRouting(Settings s) {
		super(s);
		Settings ccSettings = new Settings(CCROUTING_NS);
		updateInterval = ccSettings.getInt(UPDATE_INTERVAL);
		totalState = ccSettings.getInt(TOTAL_STATE);
		totalAction = ccSettings.getInt(TOTAL_ACTION);

		waitForReward = new HashMap<>();
		candidateReceiver = new ArrayList<>();
		dataContact = new ArrayList<>();
		listOfSumDataContact = new ArrayList<>();
		initQL();
	}

	/**
	 * Copy constructor
	 * 
	 * @param r
	 */
	protected ORQLCIRouting(ORQLCIRouting r) {
		super(r);
		updateInterval = r.updateInterval;
		totalState = r.totalState;
		totalAction = r.totalAction;

		waitForReward = new HashMap<>();
		candidateReceiver = new ArrayList<>();
		dataContact = new ArrayList<>();
		listOfSumDataContact = new ArrayList<>();
		initQL();
	}

	protected void initQL() {
		this.explorationPolicy = new EpsilonGreedyExploration(0.989);

		this.ql = new QLearning(totalState, totalAction, this.explorationPolicy, false);

		this.totalRewardWithNode = new HashMap<>();
		this.visitCount = new HashMap<>();
	}

	@Override
	public void changedConnection(Connection con) {
		super.changedConnection(con);
		
		DTNHost myHost = getHost();
		DTNHost otherNode = con.getOtherNode(myHost);

		if (con.isUp()) {

			if(!this.waitForReward.containsKey(otherNode.getAddress())) {
				this.waitForReward.put(otherNode.getAddress(), new Tuple<>(otherNode, false));
			}

			if(!this.waitForReward.get(otherNode.getAddress()).getValue().booleanValue()) {
				this.candidateReceiver.add(con);
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

		if (isTransferring() || !canStartTransfer()) {
			return; // transferring, don't try other connections yet
		}

		// Try first the messages that can be delivered to final recipient
		if (exchangeDeliverableMessages() != null) {
			return; // started a transfer, don't try others (yet)
		}

		tryOtherMessage();

		if ((SimClock.getTime() - lastUpdateTime) >= updateInterval) {

			lastUpdateTime = SimClock.getTime();

			for(Map.Entry<Integer,Tuple<DTNHost, Boolean>> entry: waitForReward.entrySet()) {
				if(entry.getKey() == newState && entry.getValue().getValue().booleanValue()) {
					DTNHost other = entry.getValue().getKey();
					ORQLCIRouting othRouter = (ORQLCIRouting) other.getRouter();

					othRouter.countCongestionRatio(); // hitung CR
					othRouter.countEma(othRouter.cr); // hitung EMA
					double reward = 1 / othRouter.ema;

					int totalVisit = visitCount.get(other) != null
						? visitCount.get(other) + 1
						: 1;

					double totalRewardForDiscFac = totalRewardWithNode.get(other) != null
						? totalRewardWithNode.get(other) + reward
						: reward;

					this.visitCount.put(other, totalVisit);
					this.totalRewardWithNode.put(other, totalRewardForDiscFac);	
				
					// Q-Learning
					int action = this.ql.GetAction(entry.getKey(), waitForReward, true);
					this.ql.setLearningRate(totalVisit);
					this.ql.setDiscountFactor(totalRewardForDiscFac );
					this.ql.UpdateState(entry.getKey(), action, reward, newState, this, other);	

					othRouter.dataReceived = 0;
					othRouter.dataTransferred = 0;

					othRouter.msgReceived = 0;
					othRouter.msgTransferred = 0;
				}
			}
		}
	}

	private Tuple<Message, Connection>  tryOtherMessage() {
		List<Tuple<Message, Connection>> messages = new ArrayList<>();
		List<Tuple<Message, Connection>> tempMessages = new ArrayList<>();

		Collection<Message> msgCollection = getMessageCollection();
		
		/**
		 * collect message terhadap node yg memiliki
		 * status available / tidak pending / waitForReward == false
		 */
		Iterator<Connection> it = candidateReceiver.iterator();
		while (it.hasNext()) {
			Connection con = it.next();
			DTNHost other = con.getOtherNode(getHost());
			ORQLCIRouting othRouter = (ORQLCIRouting) other.getRouter();

			newState = this.ql.GetAction(other.getAddress(), this.waitForReward, false);
			
			if(newState == other.getAddress()) {
				if (othRouter.isTransferring()) {
					continue; // skip hosts that are transferring
				}
	
				for (Message m : msgCollection) {
					if (othRouter.hasMessage(m.getId())) {
						continue; // skip messages that the other one has
					}
					
					// pesan yg memiliki interest sama dari node dimasukan ke list
					if(isSameInterest(m, other)) {
						tempMessages.add(new Tuple<>(m,con));
					}
				}
	
					// order by interest similarity secara desc
					Collections.sort(tempMessages, new InteresetSimilarityComparator());
		
					messages.addAll(tempMessages);
					tempMessages.clear();
		
					this.waitForReward.put(other.getAddress(), new Tuple<>(other, true));
	
					it.remove();
					it = candidateReceiver.iterator();
				}
				
		}


		if (messages.isEmpty()) {
			return null;
		}

		return tryMessagesForConnected(messages);
	}

	/**
	 * Comparator untuk sorting message berdasarkan
	 * Interest Similarity tertinggi
	 * Sort DESC
	 */
	private class InteresetSimilarityComparator implements Comparator<Tuple<Message, Connection>> {
		public int compare(Tuple<Message, Connection> tuple1, Tuple<Message, Connection> tuple2) {
			double d1 = sumList(countInterestSimilarity(tuple1.getKey(), 
													tuple1.getValue().getOtherNode(getHost())));

			double d2 = sumList(countInterestSimilarity(tuple2.getKey(), 
													tuple2.getValue().getOtherNode(getHost())));

			return Double.compare(d2, d1);
		}
	}

	@Override
	public ORQLCIRouting replicate() {
		return new ORQLCIRouting(this);
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

	public void countCongestionRatio() {
		// double dataEachContact = (this.dataReceived + this.dataTransferred) / totalContactTime;
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

		for(double lst: lists) {
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

	public Map<Integer, Tuple<DTNHost, Boolean>> getMapWaitForReward() {
		return this.waitForReward;
	}
	
}
