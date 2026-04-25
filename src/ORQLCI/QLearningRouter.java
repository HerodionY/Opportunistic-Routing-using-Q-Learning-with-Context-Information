package ORQLCI;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.Settings;
import core.SimClock;
import reinforcement.BoltzmannExploration;
import reinforcement.IExplorationPolicy;
import routing.community.Duration;
import core.SimError;
import core.Application;
import routing.ActiveRouter;

import java.util.Random;

public class QLearningRouter extends ActiveRouter {
	public static final String MESSAGE_TOPICS_S = "topic";

	// amount of possible states
	private int states;
	// amount of possible actions
	private int actions;
	// q-values
	private double[][] qvalues;
	// exploration policy
	private BoltzmannExploration explorationPolicy;

	// discount factor
	private double discountFactor = 1;
	// learning rate
	private double learningRate = 0.25;
	// growth constant
	private double growthConstant = 100;

	protected Map<DTNHost, Double> startTimestamps;
	protected Map<DTNHost, List<Duration>> connHistory;

	public QLearningRouter(Settings s) {
		super(s);
		this.startTimestamps = new HashMap<DTNHost, Double>();
		this.connHistory = new HashMap<DTNHost, List<Duration>>();
		this.explorationPolicy = new BoltzmannExploration(1);
		// create Q-array
		this.states = 5;
		this.actions = 5;

		qvalues = new double[states][];
		for (int i = 0; i < states; i++) {
			qvalues[i] = new double[actions];
		}

	}

	/**
	 * Copy constructor.
	 * 
	 * @param r The router prototype where setting values are copied from
	 */
	protected QLearningRouter(QLearningRouter r) {
		super(r);
		startTimestamps = r.startTimestamps;
		// connHistory = new HashMap<>();
		connHistory = r.connHistory;
		explorationPolicy = r.explorationPolicy;

		states = 5;
		actions = 5;
		// qvalues = r.qvalues;
		qvalues = new double[states][];
		for (int i = 0; i < states; i++) {
			qvalues[i] = new double[actions];
		}
	}

	@Override
	public boolean createNewMessage(Message msg) {
		makeRoomForNewMessage(msg.getSize());

		msg.setTtl(this.msgTtl);

		// Set topic to new message
		List<Boolean> topics = new ArrayList();

		int i = 0;
		while (i < 5) {
			topics.add(Math.random() < 0.5);
			i++;
		}

		msg.addProperty(MESSAGE_TOPICS_S, topics);
		return super.createNewMessage(msg);
	}

	@Override
	public Message messageTransferred(String id, DTNHost from) {
		Message incoming = removeFromIncomingBuffer(id, from);

		if (incoming == null) {
			throw new SimError("No message with ID " + id + " in the incoming " +
					"buffer of " + getHost());
		}

		incoming.setReceiveTime(SimClock.getTime());

		Message outgoing = incoming;
		for (Application app : getApplications(incoming.getAppID())) {
			// Note that the order of applications is significant
			// since the next one gets the output of the previous.
			outgoing = app.handle(outgoing, getHost());
			if (outgoing == null)
				break; // Some app wanted to drop the message
		}

		Message aMessage = (outgoing == null) ? (incoming) : (outgoing);

		boolean isFinalRecipient = isFinalDest(aMessage, getHost());
		boolean isFirstDelivery = isFinalRecipient &&
				!isDeliveredMessage(aMessage);

		if (outgoing != null && !isFinalRecipient) {
			// not the final recipient and app doesn't want to drop the message
			// -> put to buffer
			addToMessages(aMessage, false);
		}

		if (isFirstDelivery) {
			this.deliveredMessages.put(id, aMessage);
		}

		for (MessageListener ml : this.mListeners) {
			ml.messageTransferred(aMessage, from, getHost(),
					isFirstDelivery);
		}

		Message msg = aMessage;
		List<Boolean> topicMsg = (ArrayList) msg.getProperty(MESSAGE_TOPICS_S);
		double connectionTime = 0.0;

		if (startTimestamps.containsKey(from)) {
			double start = startTimestamps.get(from);
			double curTime = SimClock.getTime();
			connectionTime = curTime - start;

			int i = 0;
			boolean exist = false;
			double decay = 0.9;
			double disconnectionTime = 0;

			if (connHistory.containsKey(from) && !connHistory.get(from).isEmpty()) {
				// if (connHistory.containsKey(from)) {
				int conSize = connHistory.get(from).size();
				double end = connHistory.get(from).get(conSize - 1).end;
				disconnectionTime = curTime - end;
			}

			for (Boolean topic : topicMsg) {
				if (getHost().getSocialProfile().get(i) > 0 && topic == true) {
					// Check if topic from message related to node topics
					exist = true;
				} else {

					// The interest weights, i.e. Q-values of certain topics for
					// a node are aged when no other node holding messages
					// with those topics are in connection range. The aging is
					// measured as (Decay Factor)(Disconnection Time), where
					// Decay Factor is a value in a range [0, 1].

					if (connHistory.containsKey(from)) {
						double agedQValue = getHost().getSocialProfile().get(i)
								- Math.pow(decay, disconnectionTime);
						if (agedQValue < 0.5 && getHost().getSocialProfileOI().get(i)) {

							getHost().getSocialProfile().set(i, 0.5);
							// System.out.println("Decay OI" + agedQValue);

						} else if (agedQValue < 0.0) {
							getHost().getSocialProfile().set(i, 0.0);
							// System.out.println("Decay TI" + agedQValue);

						} else {
							getHost().getSocialProfile().set(i, agedQValue);
							// System.out.println("Decay" + agedQValue);
						}
					}
				}
				i++;

			}

			if (exist) {
				i = 0;
				for (Boolean topic : topicMsg) {
					if (topic) {
						int action = GetAction(i);
						double reward = connectionTime / growthConstant;
						UpdateState(i, action, reward, i, getHost());
						// System.out.println("Updated" + getHost());
					}
					i++;
				}
			}
		}
		// System.out.println(getHost().getSocialProfile());
		return msg;
	}

	@Override
	public QLearningRouter replicate() {
		return new QLearningRouter(this);
	}

	@Override
	public void changedConnection(Connection con) {
		DTNHost peer = con.getOtherNode(getHost());

		if (con.isUp()) {
			QLearningRouter othRouter = (QLearningRouter) peer.getRouter();
			this.startTimestamps.put(peer, SimClock.getTime());
			othRouter.startTimestamps.put(getHost(), SimClock.getTime());
		} else {
			if (startTimestamps.containsKey(peer)) {
				double time = startTimestamps.get(peer);
				double etime = SimClock.getTime();

				// Find or create the connection history list
				List<Duration> history;
				if (!connHistory.containsKey(peer)) {
					history = new LinkedList<Duration>();
					connHistory.put(peer, history);
				} else
					history = connHistory.get(peer);

				// add this connection to the list
				if (etime - time > 0)
					history.add(new Duration(time, etime));

				startTimestamps.remove(peer);
			}
		}
		// }
	}

	public int GetAction(int state) {
		return explorationPolicy.ChooseAction(qvalues[state]);
	}

	/**
	 * Update Q-function's value for the previous state-action pair.
	 * 
	 * @param previousState Previous state.
	 * @param action        Action, which leads from previous to the next state.
	 * @param reward        Reward value, received by taking specified action from
	 *                      previous state.
	 * @param nextState     Next state.
	 */
	public void UpdateState(int previousState, int action, double reward, int nextState, DTNHost host) {
		// next state's action estimations
		double[] nextActionEstimations = qvalues[nextState];
		// find maximum expected summary reward from the next state
		double maxNextExpectedReward = nextActionEstimations[0];

		for (int i = 1; i < actions; i++) {
			if (nextActionEstimations[i] > maxNextExpectedReward)
				maxNextExpectedReward = nextActionEstimations[i];
		}

		// previous state's action estimations
		double[] previousActionEstimations = qvalues[previousState];
		// update expexted summary reward of the previous state
		previousActionEstimations[action] *= (1.0 - learningRate);
		previousActionEstimations[action] += (learningRate * (reward + discountFactor * maxNextExpectedReward));
		host.getSocialProfile().set(previousState, previousActionEstimations[action]);
		// System.out.println(host.getSocialProfile());
	}

	// comment code dibawah karena sudah override di CCRouting
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

		// then try any/all message to any/all connection
		this.tryAllMessagesToAllConnections();
	}

	private Boolean isFinalDest(Message m, DTNHost host) {
		List<Boolean> topicMsg = (ArrayList) m.getProperty(MESSAGE_TOPICS_S);
		int i = 0;
		boolean exist = false;
		for (Boolean topic : topicMsg) {
			if (host.getSocialProfile().get(i) > 0 && topic == true) {
				// Check if topic from message related to node topics
				exist = true;
			}
		}
		return exist;
	}

	protected boolean isSameInterest(Message m, DTNHost n) {
		List<Boolean> topicMsg = (ArrayList) m.getProperty(MESSAGE_TOPICS_S);
		List<Boolean> topicNode = n.getSocialProfileOI();

		int i = 0;
		for (Iterator<Boolean> itTop = topicMsg.iterator(); itTop.hasNext(); i++) {
			if (itTop.next().equals(topicNode.get(i)))
				return true;
		}

		return false;
	}

	protected List<Double> countInterestSimilarity(Message m, DTNHost n) {
		List<Boolean> topicMsg = (ArrayList) m.getProperty(MESSAGE_TOPICS_S);
		List<Boolean> topicNode = n.getSocialProfileOI();
		List<Double> weightNode = n.getSocialProfile();

		List<Double> valInterest = new ArrayList<>();

		Iterator<Boolean> itTop = topicMsg.iterator();

		int i = 0;
		while (itTop.hasNext()) {
			if (itTop.next().equals(topicNode.get(i))) {
				valInterest.add(weightNode.get(i));
			}
		}

		return valInterest;
	}
}
