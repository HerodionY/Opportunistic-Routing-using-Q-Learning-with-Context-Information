package report;

import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.SimClock;

public class QLearningReport extends Report implements MessageListener {

    private int created;
    private int delivered;
    private int relayed;
    private int dropped;
    private double totalLatency;

    @Override
    public void newMessage(Message m) {
        created++;
    }

    @Override
    public void messageTransferStarted(Message m, DTNHost from, DTNHost to) {
        // not used in this report
    }

    @Override
    public void messageTransferAborted(Message m, DTNHost from, DTNHost to) {
        // not used in this report
    }

    @Override
    public void messageTransferred(Message m, DTNHost from, DTNHost to,
                                   boolean firstDelivery) {
        relayed++;
        if (firstDelivery) {
            delivered++;
            totalLatency += SimClock.getTime() - m.getCreationTime();
        }
    }

    @Override
    public void messageDeleted(Message m, DTNHost where, boolean dropped) {
        if (dropped) {
            this.dropped++;
        }
    }

    @Override
    public void done() {
        double deliveryRatio = (double) delivered / created;
        double overheadRatio = (double) (relayed - delivered) / delivered;
        double avgLatency = totalLatency / delivered;

        write("Delivery Ratio: " + deliveryRatio);
        write("Overhead Ratio: " + overheadRatio);
        write("Average Latency: " + avgLatency);
        write("Dropped Messages: " + dropped);
    }
}
