/* 
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package report;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.UpdateListener;
import core.SimClock;
import core.Settings;

/**
 * Report for generating statistics about message relaying performance over time.
 * This class outputs a log at defined intervals, making it easy to plot time-series lines.
 * 
 * Format:
 * time delivery_prob overhead_ratio latency_avg dropped created delivered response_prob delivery_prob_pct response_prob_pct drop_rate_pct
 */
public class MessageStatsTimeReport extends Report implements MessageListener, UpdateListener {
	
	public static final String REPORT_INTERVAL_SETTING = "interval";
	public static final int DEFAULT_REPORT_INTERVAL = 1000;
	
	private int interval;
	private double nextReportTime;

	private Map<String, Double> creationTimes;
	private List<Double> latencies;
	private List<Integer> hopCounts;
	private List<Double> msgBufferTime;
	private List<Double> rtt; // round trip times
	
	private int nrofDropped;
	private int nrofRemoved;
	private int nrofStarted;
	private int nrofAborted;
	private int nrofRelayed;
	private int nrofCreated;
	private int nrofResponseReqCreated;
	private int nrofResponseDelivered;
	private int nrofDelivered;
	
	/**
	 * Constructor.
	 */
	public MessageStatsTimeReport() {
		init();
	}

	@Override
	protected void init() {
		super.init();
		
		Settings settings = getSettings();
		if (settings.contains(REPORT_INTERVAL_SETTING)) {
			interval = settings.getInt(REPORT_INTERVAL_SETTING);
		} else {
			interval = DEFAULT_REPORT_INTERVAL;
		}
		
		// Mulai record pada interval pertama
		nextReportTime = interval;

		this.creationTimes = new HashMap<String, Double>();
		this.latencies = new ArrayList<Double>();
		this.msgBufferTime = new ArrayList<Double>();
		this.hopCounts = new ArrayList<Integer>();
		this.rtt = new ArrayList<Double>();
		
		this.nrofDropped = 0;
		this.nrofRemoved = 0;
		this.nrofStarted = 0;
		this.nrofAborted = 0;
		this.nrofRelayed = 0;
		this.nrofCreated = 0;
		this.nrofResponseReqCreated = 0;
		this.nrofResponseDelivered = 0;
		this.nrofDelivered = 0;
		
		// Tulis header agar mudah diparsing Python
		write("time delivery_prob overhead_ratio latency_avg dropped created delivered response_prob delivery_prob_pct response_prob_pct drop_rate_pct");
	}

	@Override
	public void updated(List<DTNHost> hosts) {
		if (isWarmup()) {
			return;
		}
		
		// Ketika waktu simulasi mencapai interval yang ditentukan, catat metric
		if (SimClock.getTime() >= nextReportTime) {
			printStats();
			nextReportTime += interval;
		}
	}

	private void printStats() {
		double deliveryProb = 0; 
		double responseProb = 0;
		double overHead = 0.0;	
		double deliveryProbPct = 0;
		double responseProbPct = 0;
		double dropRatePct = 0;
		
		if (this.nrofCreated > 0) {
			deliveryProb = (1.0 * this.nrofDelivered) / this.nrofCreated;
		}
		if (this.nrofDelivered > 0) {
			overHead = (1.0 * (this.nrofRelayed - this.nrofDelivered)) / this.nrofDelivered;
		}
		if (this.nrofResponseReqCreated > 0) {
			responseProb = (1.0 * this.nrofResponseDelivered) / this.nrofResponseReqCreated;
		}
		deliveryProbPct = deliveryProb * 100.0;
		responseProbPct = responseProb * 100.0;
		if (this.nrofCreated > 0) {
			dropRatePct = (1.0 * this.nrofDropped) / this.nrofCreated * 100.0;
		}
		
		String latAvgStr = getAverage(this.latencies);
		if (latAvgStr.equals(NAN)) {
			latAvgStr = "0.0";
		}

		// Format output: time, delivery, overhead, latency, dropped, created, delivered, response_prob, delivery_prob_pct, response_prob_pct, drop_rate_pct
		String output = format(SimClock.getTime()) + " " + 
			format(deliveryProb) + " " + 
			format(overHead) + " " + 
			latAvgStr + " " +
			this.nrofDropped + " " +
			this.nrofCreated + " " +
			this.nrofDelivered + " " +
			format(responseProb) + " " +
			format(deliveryProbPct) + " " +
			format(responseProbPct) + " " +
			format(dropRatePct);

		write(output);
	}

	@Override
	public void messageDeleted(Message m, DTNHost where, boolean dropped) {
		if (isWarmupID(m.getId())) {
			return;
		}
		
		if (dropped) {
			this.nrofDropped++;
		}
		else {
			this.nrofRemoved++;
		}
		this.msgBufferTime.add(getSimTime() - m.getReceiveTime());
	}

	@Override
	public void messageTransferAborted(Message m, DTNHost from, DTNHost to) {
		if (isWarmupID(m.getId())) {
			return;
		}
		this.nrofAborted++;
	}

	@Override
	public void messageTransferred(Message m, DTNHost from, DTNHost to, boolean finalTarget) {
		if (isWarmupID(m.getId())) {
			return;
		}

		this.nrofRelayed++;
		if (finalTarget) {
			this.latencies.add(getSimTime() - this.creationTimes.get(m.getId()));
			this.nrofDelivered++;
			this.hopCounts.add(m.getHops().size() - 1);

			if (m.isResponse()) {
				this.rtt.add(getSimTime() - m.getRequest().getCreationTime());
				this.nrofResponseDelivered++;
			}
		}
	}

	@Override
	public void newMessage(Message m) {
		if (isWarmup()) {
			addWarmupID(m.getId());
			return;
		}
		
		this.creationTimes.put(m.getId(), getSimTime());
		this.nrofCreated++;
		if (m.getResponseSize() > 0) {
			this.nrofResponseReqCreated++;
		}
	}
	
	@Override
	public void messageTransferStarted(Message m, DTNHost from, DTNHost to) {
		if (isWarmupID(m.getId())) {
			return;
		}
		this.nrofStarted++;
	}
	
	@Override
	public void done() {
		// Output statistik terakhir persis seperti terakhir berjalan
		printStats();
		super.done();
	}
}
