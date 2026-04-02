package report;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import core.DTNHost;
import core.Message;
import core.MessageListener;

/**
 * Report for Performance benchmarking of ORQLCI and other algorithms.
 * Measures Delivery Ratio, Overhead Ratio, and Average Latency.
 * 
 * Reference: Liu, et al., 2025
 */
public class PerformanceReport extends Report implements MessageListener {
    private Map<String, Double> creationTimes;
    private List<Double> latencies;
    
    private int nrofRelayed;
    private int nrofCreated;
    private int nrofDelivered;

    public PerformanceReport() {
        init();
    }

    @Override
    protected void init() {
        super.init();
        this.creationTimes = new HashMap<String, Double>();
        this.latencies = new ArrayList<Double>();
        
        this.nrofRelayed = 0;
        this.nrofCreated = 0;
        this.nrofDelivered = 0;
    }

    private String routerClassName = "Unknown";

    @Override
    public void newMessage(Message m) {
        if (isWarmup()) {
            addWarmupID(m.getId());
            return;
        }

        if (routerClassName.equals("Unknown")) {
            routerClassName = m.getFrom().getRouter().getClass().getSimpleName();
        }

        creationTimes.put(m.getId(), getSimTime());
        nrofCreated++;
    }

    @Override
    public void messageTransferred(Message m, DTNHost from, DTNHost to, boolean finalTarget) {
        if (isWarmupID(m.getId())) {
            return;
        }

        nrofRelayed++;
        if (finalTarget) {
            Double creationTime = creationTimes.get(m.getId());
            if (creationTime != null) {
                latencies.add(getSimTime() - creationTime);
            }
            nrofDelivered++;
        }
    }

    @Override
    public void messageDeleted(Message m, DTNHost where, boolean dropped) {
        // Not used for these specific metrics but part of MessageListener
    }

    @Override
    public void messageTransferAborted(Message m, DTNHost from, DTNHost to) {
        // Not used for these specific metrics 
    }

    @Override
    public void messageTransferStarted(Message m, DTNHost from, DTNHost to) {
        // Not used for these specific metrics
    }

    @Override
    public void done() {
        write("Performance Report for scenario " + getScenarioName());
        write("Simulation time: " + format(getSimTime()));
        write("");

        double deliveryRatio = 0;
        double overheadRatio = Double.NaN;
        double deliveryRatioPct = 0;

        if (nrofCreated > 0) {
            deliveryRatio = (1.0 * nrofDelivered) / nrofCreated;
        }
        deliveryRatioPct = deliveryRatio * 100.0;

        if (nrofDelivered > 0) {
            overheadRatio = (1.0 * (nrofRelayed - nrofDelivered)) / nrofDelivered;
        }

        write("Delivery Ratio: " + format(deliveryRatio));
        write("Delivery Ratio (%): " + format(deliveryRatioPct));
        write("Overhead Ratio: " + format(overheadRatio));
        write("Average Latency: " + getAverage(latencies));
        
        write("");
        write("Router used: " + routerClassName);
        write("Created Messages: " + nrofCreated);
        write("Relayed Messages: " + nrofRelayed);
        write("Delivered Messages: " + nrofDelivered);

        super.done();
    }
}
