/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.ModuleCommunicationBus;
import core.NetworkInterface;
import core.Settings;
import core.SimClock;
import core.SimScenario;
import core.Tuple;

/**
 * Implementation of PRoPHET router as described in 
 * <I>Probabilistic routing in intermittently connected networks</I> by
 * Anders Lindgren et al.
 */
public class ProphetRouter extends ActiveRouter {
	/** delivery predictability initialization constant*/
	public static final double P_INIT = 0.75;
	/** delivery predictability transitivity scaling constant default value */
	public static final double DEFAULT_BETA = 0.25;
	/** delivery predictability aging constant */
	public static final double GAMMA = 0.98;
	
	/** Prophet router's setting namespace ({@value})*/ 
	public static final String PROPHET_NS = "ProphetRouter";
	/**
	 * Number of seconds in time unit -setting id ({@value}).
	 * How many seconds one time unit is when calculating aging of 
	 * delivery predictions. Should be tweaked for the scenario.*/
	public static final String SECONDS_IN_UNIT_S ="secondsInTimeUnit";
	
	/**
	 * Transitivity scaling constant (beta) -setting id ({@value}).
	 * Default value for setting is {@link #DEFAULT_BETA}.
	 */
	public static final String BETA_S = "beta";
	/** Energy-awareness settings (optional) */
	public static final String ENERGY_AWARE_ENABLED = "energyAwareEnabled";
	public static final String ENERGY_THRESHOLD = "energyThreshold";
	public static final String ENERGY_INIT = "energyInit";
	public static final String ENERGY_INIT_DELTA = "energyInitDelta";
	public static final String ENERGY_SCAN = "scanEnergy";
	public static final String ENERGY_TRANSMIT = "transmitEnergy";
	public static final String ENERGY_WARMUP = "energyWarmup";

	/** the value of nrof seconds in time unit -setting */
	private int secondsInTimeUnit;
	/** value of beta setting */
	private double beta;

	/** delivery predictabilities */
	private Map<DTNHost, Double> preds;
	/** last delivery predictability update (sim)time */
	private double lastAgeUpdate;

	// Energy awareness
	private boolean energyAwareEnabled = false;
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
	
	/**
	 * Constructor. Creates a new message router based on the settings in
	 * the given Settings object.
	 * @param s The settings object
	 */
	public ProphetRouter(Settings s) {
		super(s);
		Settings prophetSettings = new Settings(PROPHET_NS);
		secondsInTimeUnit = prophetSettings.getInt(SECONDS_IN_UNIT_S);
		if (prophetSettings.contains(BETA_S)) {
			beta = prophetSettings.getDouble(BETA_S);
		}
		else {
			beta = DEFAULT_BETA;
		}

		if (prophetSettings.contains(ENERGY_AWARE_ENABLED)) {
			energyAwareEnabled = prophetSettings.getBoolean(ENERGY_AWARE_ENABLED);
		}
		if (prophetSettings.contains(ENERGY_THRESHOLD)) {
			energyThreshold = prophetSettings.getDouble(ENERGY_THRESHOLD);
		}
		if (prophetSettings.contains(ENERGY_INIT)) {
			energyInit = prophetSettings.getDouble(ENERGY_INIT);
		}
		if (prophetSettings.contains(ENERGY_INIT_DELTA)) {
			energyInitDelta = prophetSettings.getDouble(ENERGY_INIT_DELTA);
		}
		if (prophetSettings.contains(ENERGY_SCAN)) {
			scanEnergy = prophetSettings.getDouble(ENERGY_SCAN);
		}
		if (prophetSettings.contains(ENERGY_TRANSMIT)) {
			transmitEnergy = prophetSettings.getDouble(ENERGY_TRANSMIT);
		}
		if (prophetSettings.contains(ENERGY_WARMUP)) {
			warmupTime = prophetSettings.getDouble(ENERGY_WARMUP);
		}
		if (s.contains(SimScenario.SCAN_INTERVAL_S)) {
			scanInterval = s.getDouble(SimScenario.SCAN_INTERVAL_S);
		} else {
			scanInterval = 0.0;
		}

		initEnergy();
		initPreds();
	}

	/**
	 * Copyconstructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected ProphetRouter(ProphetRouter r) {
		super(r);
		this.secondsInTimeUnit = r.secondsInTimeUnit;
		this.beta = r.beta;
		this.energyAwareEnabled = r.energyAwareEnabled;
		this.energyThreshold = r.energyThreshold;
		this.energyInit = r.energyInit;
		this.energyInitDelta = r.energyInitDelta;
		this.scanEnergy = r.scanEnergy;
		this.transmitEnergy = r.transmitEnergy;
		this.warmupTime = r.warmupTime;
		this.scanInterval = r.scanInterval;
		this.comBus = null;
		this.lastEnergyUpdate = 0.0;
		this.lastScanUpdate = 0.0;
		initEnergy();
		initPreds();
	}
	
	/**
	 * Initializes predictability hash
	 */
	private void initPreds() {
		this.preds = new HashMap<DTNHost, Double>();
	}

	private void initEnergy() {
		if (!energyAwareEnabled) {
			energyCapacity = 0.0;
			currentEnergy = 0.0;
			return;
		}

		if (energyThreshold < 0.0) {
			energyThreshold = 0.0;
		} else if (energyThreshold > 1.0) {
			energyThreshold = 1.0;
		}
		if (energyInit < 0.0) {
			energyInit = 0.0;
		}
		if (energyInitDelta < 0.0) {
			energyInitDelta = 0.0;
		}
		if (energyInitDelta > energyInit) {
			energyInitDelta = energyInit;
		}

		double min = energyInit - energyInitDelta;
		if (energyRng == null) {
			energyRng = new Random((int)(energyInit + energyInitDelta));
		}
		if (energyInitDelta > 0.0) {
			energyCapacity = min + (energyRng.nextDouble() * (energyInit - min));
		} else {
			energyCapacity = energyInit;
		}
		if (energyCapacity < 0.0) {
			energyCapacity = 0.0;
		}
		currentEnergy = energyCapacity;
	}

	@Override
	public void init(DTNHost host, List<MessageListener> mListeners) {
		super.init(host, mListeners);
		if (energyAwareEnabled) {
			this.comBus = host.getComBus();
			if (this.comBus != null) {
				this.comBus.updateProperty(EnergyAwareRouter.ENERGY_VALUE_ID, this.currentEnergy);
				this.comBus.updateProperty(ENERGY_CAPACITY_ID, this.energyCapacity);
			}
		}
	}

	private void reduceSendingAndScanningEnergy() {
		if (!energyAwareEnabled) {
			return;
		}
		double simTime = SimClock.getTime();

		if (this.comBus == null) {
			this.comBus = getHost().getComBus();
			if (this.comBus != null) {
				this.comBus.updateProperty(EnergyAwareRouter.ENERGY_VALUE_ID, this.currentEnergy);
				this.comBus.updateProperty(ENERGY_CAPACITY_ID, this.energyCapacity);
			}
		}

		if (this.currentEnergy <= 0.0) {
			if (this.comBus != null) {
				this.comBus.updateProperty(NetworkInterface.RANGE_ID, 0.0);
			}
			return;
		}

		if (simTime > this.lastEnergyUpdate && sendingConnections.size() > 0) {
			reduceEnergy((simTime - this.lastEnergyUpdate) * this.transmitEnergy);
		}
		this.lastEnergyUpdate = simTime;

		if (this.scanInterval > 0 && simTime > this.lastScanUpdate + this.scanInterval) {
			reduceEnergy(this.scanEnergy);
			this.lastScanUpdate = simTime;
		}
	}

	private void reduceEnergy(double amount) {
		if (SimClock.getTime() < this.warmupTime) {
			return;
		}
		if (amount <= 0.0) {
			return;
		}

		this.currentEnergy -= amount;
		if (this.currentEnergy < 0.0) {
			this.currentEnergy = 0.0;
		}
		if (this.comBus != null) {
			this.comBus.updateProperty(EnergyAwareRouter.ENERGY_VALUE_ID, this.currentEnergy);
		}
	}

	@Override
	public void changedConnection(Connection con) {
		if (con.isUp()) {
			DTNHost otherHost = con.getOtherNode(getHost());
			updateDeliveryPredFor(otherHost);
			updateTransitivePreds(otherHost);
		}
	}
	
	/**
	 * Updates delivery predictions for a host.
	 * <CODE>P(a,b) = P(a,b)_old + (1 - P(a,b)_old) * P_INIT</CODE>
	 * @param host The host we just met
	 */
	private void updateDeliveryPredFor(DTNHost host) {
		double oldValue = getPredFor(host);
		double newValue = oldValue + (1 - oldValue) * P_INIT;
		preds.put(host, newValue);
	}
	
	/**
	 * Returns the current prediction (P) value for a host or 0 if entry for
	 * the host doesn't exist.
	 * @param host The host to look the P for
	 * @return the current P value
	 */
	public double getPredFor(DTNHost host) {
		ageDeliveryPreds(); // make sure preds are updated before getting
		if (preds.containsKey(host)) {
			return preds.get(host);
		}
		else {
			return 0;
		}
	}
	
	/**
	 * Updates transitive (A->B->C) delivery predictions.
	 * <CODE>P(a,c) = P(a,c)_old + (1 - P(a,c)_old) * P(a,b) * P(b,c) * BETA
	 * </CODE>
	 * @param host The B host who we just met
	 */
	private void updateTransitivePreds(DTNHost host) {
		MessageRouter otherRouter = host.getRouter();
		assert otherRouter instanceof ProphetRouter : "PRoPHET only works " + 
			" with other routers of same type";
		
		double pForHost = getPredFor(host); // P(a,b)
		Map<DTNHost, Double> othersPreds = 
			((ProphetRouter)otherRouter).getDeliveryPreds();
		
		for (Map.Entry<DTNHost, Double> e : othersPreds.entrySet()) {
			if (e.getKey() == getHost()) {
				continue; // don't add yourself
			}
			
			double pOld = getPredFor(e.getKey()); // P(a,c)_old
			double pNew = pOld + ( 1 - pOld) * pForHost * e.getValue() * beta;
			preds.put(e.getKey(), pNew);
		}
	}

	/**
	 * Ages all entries in the delivery predictions.
	 * <CODE>P(a,b) = P(a,b)_old * (GAMMA ^ k)</CODE>, where k is number of
	 * time units that have elapsed since the last time the metric was aged.
	 * @see #SECONDS_IN_UNIT_S
	 */
	private void ageDeliveryPreds() {
		double timeDiff = (SimClock.getTime() - this.lastAgeUpdate) / 
			secondsInTimeUnit;
		
		if (timeDiff == 0) {
			return;
		}
		
		double mult = Math.pow(GAMMA, timeDiff);
		for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
			e.setValue(e.getValue()*mult);
		}
		
		this.lastAgeUpdate = SimClock.getTime();
	}
	
	/**
	 * Returns a map of this router's delivery predictions
	 * @return a map of this router's delivery predictions
	 */
	private Map<DTNHost, Double> getDeliveryPreds() {
		ageDeliveryPreds(); // make sure the aging is done
		return this.preds;
	}
	
	@Override
	public void update() {
		super.update();
		reduceSendingAndScanningEnergy();
		if (!canStartTransfer() ||isTransferring()) {
			return; // nothing to transfer or is currently transferring 
		}
		
		// try messages that could be delivered to final recipient
		if (exchangeDeliverableMessages() != null) {
			return;
		}
		
		tryOtherMessages();		
	}
	
	/**
	 * Tries to send all other messages to all connected hosts ordered by
	 * their delivery probability
	 * @return The return value of {@link #tryMessagesForConnected(List)}
	 */
	private Tuple<Message, Connection> tryOtherMessages() {
		List<Tuple<Message, Connection>> messages = 
			new ArrayList<Tuple<Message, Connection>>(); 
	
		Collection<Message> msgCollection = getMessageCollection();
		
		/* for all connected hosts collect all messages that have a higher
		   probability of delivery by the other host */
		for (Connection con : getConnections()) {
			DTNHost other = con.getOtherNode(getHost());
			ProphetRouter othRouter = (ProphetRouter)other.getRouter();
			
			if (othRouter.isTransferring()) {
				continue; // skip hosts that are transferring
			}
			
			for (Message m : msgCollection) {
				if (othRouter.hasMessage(m.getId())) {
					continue; // skip messages that the other one has
				}

				if (othRouter.getPredFor(m.getTo()) > getPredFor(m.getTo())) {
					// the other node has higher probability of delivery
					messages.add(new Tuple<Message, Connection>(m,con));
				}
			}			
		}
		
		if (messages.size() == 0) {
			return null;
		}
		
		// sort the message-connection tuples
		Collections.sort(messages, new TupleComparator());
		return tryMessagesForConnected(messages);	// try to send messages
	}
	
	/**
	 * Comparator for Message-Connection-Tuples that orders the tuples by
	 * their delivery probability by the host on the other side of the 
	 * connection (GRTRMax)
	 */
	private class TupleComparator implements Comparator 
		<Tuple<Message, Connection>> {

		public int compare(Tuple<Message, Connection> tuple1,
				Tuple<Message, Connection> tuple2) {
			// delivery probability of tuple1's message with tuple1's connection
			double p1 = ((ProphetRouter)tuple1.getValue().
					getOtherNode(getHost()).getRouter()).getPredFor(
					tuple1.getKey().getTo());
			// -"- tuple2...
			double p2 = ((ProphetRouter)tuple2.getValue().
					getOtherNode(getHost()).getRouter()).getPredFor(
					tuple2.getKey().getTo());

			// bigger probability should come first
			if (p2-p1 == 0) {
				/* equal probabilities -> let queue mode decide */
				return compareByQueueMode(tuple1.getKey(), tuple2.getKey());
			}
			else if (p2-p1 < 0) {
				return -1;
			}
			else {
				return 1;
			}
		}
	}
	
	@Override
	public RoutingInfo getRoutingInfo() {
		ageDeliveryPreds();
		RoutingInfo top = super.getRoutingInfo();
		RoutingInfo ri = new RoutingInfo(preds.size() + 
				" delivery prediction(s)");
		
		for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
			DTNHost host = e.getKey();
			Double value = e.getValue();
			
			ri.addMoreInfo(new RoutingInfo(String.format("%s : %.6f", 
					host, value)));
		}
		
		top.addMoreInfo(ri);
		return top;
	}
	
	@Override
	public MessageRouter replicate() {
		ProphetRouter r = new ProphetRouter(this);
		return r;
	}

}
