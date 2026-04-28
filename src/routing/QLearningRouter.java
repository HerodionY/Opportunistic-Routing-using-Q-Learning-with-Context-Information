package routing;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import routing.community.Duration;

public abstract class QLearningRouter extends ActiveRouter {

	public static final String MESSAGE_TOPICS_S = "topic";
	protected Map<Integer, Map<Integer, Double>> qvalues;

	protected Map<Integer, Map<Integer, Double>> lastQUpdateTimes;

	protected double learningRate = 0.8; // α - learning coefficient
	protected double discountFactor = 0.6; // γ - base discount factor
	protected double agingOmega = 0.98; // ω - aging constant (Eq. 11)
	protected int qAgeTimeUnit = 30; // Time unit untuk aging (30s)

	protected Map<DTNHost, Double> startTimestamps;
	protected Map<DTNHost, List<Duration>> connHistory;

	public QLearningRouter(Settings s) {
		super(s);
		this.startTimestamps = new HashMap<>();
		this.connHistory = new HashMap<>();
		initQTable();
	}

	protected QLearningRouter(QLearningRouter r) {
		super(r);
		this.startTimestamps = new HashMap<>();
		this.connHistory = new HashMap<>();
		this.learningRate = r.learningRate;
		this.discountFactor = r.discountFactor;
		this.agingOmega = r.agingOmega;
		this.qAgeTimeUnit = r.qAgeTimeUnit;
		initQTable();
	}

	protected void initQTable() {
		qvalues = new HashMap<>();
		lastQUpdateTimes = new HashMap<>();
	}


	private void ensureQEntry(int destAddr, int actionAddr) {
		qvalues.putIfAbsent(destAddr, new HashMap<>());
		lastQUpdateTimes.putIfAbsent(destAddr, new HashMap<>());

		qvalues.get(destAddr).putIfAbsent(actionAddr, 0.0);
		lastQUpdateTimes.get(destAddr).putIfAbsent(actionAddr, SimClock.getTime());
	}

	private boolean isValidAddress(int addr) {
		return addr >= 0;
	}

	private void ageQEntry(int destAddr, int actionAddr, int secInTimeUnit) {
		if (!isValidAddress(destAddr) || !isValidAddress(actionAddr)) {
			return;
		}
		if (secInTimeUnit <= 0) {
			return;
		}

		Map<Integer, Double> actionMap = qvalues.get(destAddr);
		if (actionMap == null || !actionMap.containsKey(actionAddr)) {
			return;
		}

		double now = SimClock.getTime();
		double lastUpdate = lastQUpdateTimes.get(destAddr).get(actionAddr);
		double timeDiff = (now - lastUpdate) / secInTimeUnit;

		if (timeDiff <= 0) {
			return;
		}

		double currentQ = actionMap.get(actionAddr);
		double agedQ = currentQ * Math.pow(agingOmega, timeDiff);

		actionMap.put(actionAddr, agedQ);
		lastQUpdateTimes.get(destAddr).put(actionAddr, now);
	}

	public double getQV(int destAddr, int actionAddr) {
		if (!isValidAddress(destAddr) || !isValidAddress(actionAddr)) {
			return 0.0;
		}

		Map<Integer, Double> actionMap = qvalues.get(destAddr);
		if (actionMap == null || !actionMap.containsKey(actionAddr)) {
			return 0.0;
		}
		ageQEntry(destAddr, actionAddr, qAgeTimeUnit);
		return qvalues.get(destAddr).get(actionAddr);
	}

	public void updateQDirect(int destAddr, int relayAddr) {
		if (!isValidAddress(destAddr) || !isValidAddress(relayAddr)) {
			return;
		}

		ensureQEntry(destAddr, relayAddr);

		double oldQ = qvalues.get(destAddr).get(relayAddr);

		double newQ = (1.0 - learningRate) * oldQ + learningRate * 1.0;

		qvalues.get(destAddr).put(relayAddr, newQ);
		lastQUpdateTimes.get(destAddr).put(relayAddr, SimClock.getTime());
	}

	public void updateQRelay(int destAddr, int relayAddr,
			double dynamicDiscount, double neighborMaxQP) {

		if (!isValidAddress(destAddr) || !isValidAddress(relayAddr)) {
			return;
		}

		ensureQEntry(destAddr, relayAddr);

		double oldQ = qvalues.get(destAddr).get(relayAddr);

		double newQ = (1.0 - learningRate) * oldQ
				+ learningRate * dynamicDiscount * neighborMaxQP;

		qvalues.get(destAddr).put(relayAddr, newQ);
		lastQUpdateTimes.get(destAddr).put(relayAddr, SimClock.getTime());
	}

	public void ageQTable(int secInTimeUnit) {
		if (secInTimeUnit <= 0) {
			return;
		}

		for (Map.Entry<Integer, Map<Integer, Double>> destEntry : qvalues.entrySet()) {
			int destAddr = destEntry.getKey();
			for (Integer actionAddr : destEntry.getValue().keySet()) {
				ageQEntry(destAddr, actionAddr, secInTimeUnit);
			}
		}
	}

	public double getNeighborMaxQPrime(int destAddr,
			Map<Integer, Double> encounterProbs) {

		if (!isValidAddress(destAddr)) {
			return 0.0;
		}
		if (encounterProbs == null || encounterProbs.isEmpty()) {
			return 0.0;
		}

		double maxVal = 0.0;

		for (Map.Entry<Integer, Double> entry : encounterProbs.entrySet()) {
			int neighborAddr = entry.getKey();
			double prob = entry.getValue();

			if (!isValidAddress(neighborAddr)) {
				continue;
			}

			double qVal = getQV(destAddr, neighborAddr);
			double val = qVal * prob;

			if (val > maxVal) {
				maxVal = val;
			}
		}

		return maxVal;
	}

	private static final double Q_MEANINGFUL_THRESHOLD = 1e-4;

	public boolean hasQEntry(int destAddr) {
		if (!isValidAddress(destAddr)) {
			return false;
		}

		Map<Integer, Double> actionMap = qvalues.get(destAddr);
		if (actionMap == null || actionMap.isEmpty()) {
			return false;
		}
		for (Map.Entry<Integer, Double> entry : actionMap.entrySet()) {
			int actionAddr = entry.getKey();
			ageQEntry(destAddr, actionAddr, qAgeTimeUnit);
			if (qvalues.get(destAddr).get(actionAddr) > Q_MEANINGFUL_THRESHOLD) {
				return true;
			}
		}

		return false;
	}

	public int getBestAction(int destAddr) {
		if (!isValidAddress(destAddr)) {
			return -1;
		}

		Map<Integer, Double> actionMap = qvalues.get(destAddr);
		if (actionMap == null || actionMap.isEmpty()) {
			return -1;
		}

		int bestAction = -1;
		double bestVal = Double.NEGATIVE_INFINITY;

		for (Map.Entry<Integer, Double> entry : actionMap.entrySet()) {
			int actionAddr = entry.getKey();
			ageQEntry(destAddr, actionAddr, qAgeTimeUnit);
			double qVal = qvalues.get(destAddr).get(actionAddr);

			if (qVal > bestVal) {
				bestVal = qVal;
				bestAction = actionAddr;
			}
		}

		return bestAction;
	}

	public double getBestQValue(int destAddr) {
		if (!isValidAddress(destAddr)) {
			return 0.0;
		}

		Map<Integer, Double> actionMap = qvalues.get(destAddr);
		if (actionMap == null || actionMap.isEmpty()) {
			return 0.0;
		}

		double bestVal = 0.0;

		for (Map.Entry<Integer, Double> entry : actionMap.entrySet()) {
			int actionAddr = entry.getKey();
			ageQEntry(destAddr, actionAddr, qAgeTimeUnit);
			double qVal = qvalues.get(destAddr).get(actionAddr);

			if (qVal > bestVal) {
				bestVal = qVal;
			}
		}

		return bestVal;
	}

	public List<Integer> getAvailableActions(int destAddr) {
		List<Integer> actions = new ArrayList<>();

		if (!isValidAddress(destAddr)) {
			return actions;
		}

		if (!qvalues.containsKey(destAddr)) {
			return actions;
		}

		actions.addAll(qvalues.get(destAddr).keySet());
		return actions;
	}

	@Override
	public void changedConnection(Connection con) {
		DTNHost peer = con.getOtherNode(getHost());

		if (con.isUp()) {
			startTimestamps.put(peer, SimClock.getTime());
		} else {
			if (startTimestamps.containsKey(peer)) {
				double start = startTimestamps.remove(peer);
				double end = SimClock.getTime();

				if (end - start > 0) {
					connHistory.computeIfAbsent(peer, k -> new LinkedList<>())
							.add(new Duration(start, end));
				}
			}
		}
	}


	@Override
	public boolean createNewMessage(Message msg) {
		makeRoomForNewMessage(msg.getSize());
		msg.setTtl(this.msgTtl);

		List<Boolean> topics = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			topics.add(Math.random() < 0.5);
		}
		msg.addProperty(MESSAGE_TOPICS_S, topics);

		return super.createNewMessage(msg);
	}

	@Override
	public abstract QLearningRouter replicate();

	@Override
	public void update() {
		super.update();
	}

	public double getLearningRate() {
		return learningRate;
	}

	public double getDiscountFactor() {
		return discountFactor;
	}

	public double getAgingOmega() {
		return agingOmega;
	}

	public String getQTableStats() {
		int totalDests = qvalues.size();
		int totalEntries = 0;
		double totalQValue = 0.0;

		for (Map<Integer, Double> actionMap : qvalues.values()) {
			totalEntries += actionMap.size();
			for (Double qVal : actionMap.values()) {
				totalQValue += qVal;
			}
		}

		double avgQ = totalEntries > 0 ? totalQValue / totalEntries : 0.0;

		return String.format("Q-Table: %d dests, %d entries, avg Q=%.4f",
				totalDests, totalEntries, avgQ);
	}
}