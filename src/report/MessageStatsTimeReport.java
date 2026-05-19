package report;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.Settings;

/**
 * Laporan untuk menghasilkan statistik pesan secara periodik berdasarkan interval waktu.
 */
public class MessageStatsTimeReport extends Report implements MessageListener {
    
    public static final String REPORT_INTERVAL = "interval";
    private double interval;
    private double lastReportTime;

    private Map<String, Double> creationTimes;
    private Set<String> deliveredMessages;
    private List<Double> latencies;
    private List<Integer> hopCounts;
    private List<Double> msgBufferTime;
    private List<Double> rtt;

    private int nrofDropped;
    private int nrofRemoved;
    private int nrofStarted;
    private int nrofAborted;
    private int nrofRelayed;
    private int nrofCreated;
    private int nrofResponseReqCreated;
    private int nrofResponseDelivered;
    private int nrofDelivered;

    public MessageStatsTimeReport() {
        Settings s = new Settings();
        if (s.contains(REPORT_INTERVAL)) {
            interval = s.getDouble(REPORT_INTERVAL);
        } else {
            interval = -1; // Jika tidak diatur, hanya cetak di akhir simulasi
        }
        this.lastReportTime = 0;
        init();
    }

    @Override
    protected void init() {
        super.init();
        this.creationTimes = new HashMap<String, Double>();
        this.deliveredMessages = new HashSet<String>();
        this.latencies = new ArrayList<Double>();
        this.msgBufferTime = new ArrayList<Double>();
        this.hopCounts = new ArrayList<Integer>();
        this.rtt = new ArrayList<Double>();
        resetCounters();
    }

    private void resetCounters() {
        this.nrofDropped = 0;
        this.nrofRemoved = 0;
        this.nrofStarted = 0;
        this.nrofAborted = 0;
        this.nrofRelayed = 0;
        this.nrofCreated = 0;
        this.nrofResponseReqCreated = 0;
        this.nrofResponseDelivered = 0;
        this.nrofDelivered = 0;
    }

    /**
     * Mengecek apakah waktu simulasi saat ini sudah melewati interval laporan berikutnya.
     */
    private void checkPeriod() {
        if (interval < 0) return;
        double currentTime = getSimTime();
        
        if (currentTime >= lastReportTime + interval) {
            writeReport(currentTime);
            lastReportTime = currentTime - (currentTime % interval);
            // Opsional: Panggil resetCounters() jika ingin data per interval murni
        }
    }

    private void writeReport(double time) {
        double deliveryProb = 0;
        double overHead = Double.NaN;

        if (this.nrofCreated > 0) {
            deliveryProb = (1.0 * this.nrofDelivered) / this.nrofCreated;
        }
        if (this.nrofDelivered > 0) {
            overHead = (1.0 * (this.nrofRelayed - this.nrofDelivered)) / this.nrofDelivered;
        }

        String stats = String.format("\n--- Stats Periodik pada %.2f ---\n", time) +
                "Created: " + this.nrofCreated +
                "\nDelivered: " + this.nrofDelivered +
                "\nDelivery_Prob: " + format(deliveryProb) +
                "\nOverhead_Ratio: " + format(overHead) +
                "\nLatency_Avg: " + getAverage(this.latencies) +
                "\nHopCount_Avg: " + getIntAverage(this.hopCounts) +
                "\n-------------------------------";
        
        write(stats);
    }

    @Override
    public void newMessage(Message m) {
        if (isWarmup()) { addWarmupID(m.getId()); return; }
        checkPeriod();
        this.creationTimes.put(m.getId(), getSimTime());
        this.nrofCreated++;
    }

    @Override
    public void messageTransferred(Message m, DTNHost from, DTNHost to, boolean finalTarget) {
        if (isWarmupID(m.getId())) return;
        checkPeriod();
        this.nrofRelayed++;
        if (finalTarget && !deliveredMessages.contains(m.getId())) {
            this.deliveredMessages.add(m.getId());
            this.nrofDelivered++;
            this.latencies.add(getSimTime() - this.creationTimes.get(m.getId()));
            this.hopCounts.add(m.getHops().size() - 1);
        }
    }

    @Override
    public void messageDeleted(Message m, DTNHost where, boolean dropped) {
        if (isWarmupID(m.getId())) return;
        checkPeriod();
        if (dropped) this.nrofDropped++;
        else this.nrofRemoved++;
    }

    @Override
    public void messageTransferAborted(Message m, DTNHost from, DTNHost to) {
        if (isWarmupID(m.getId())) return;
        checkPeriod();
        this.nrofAborted++;
    }

    @Override
    public void messageTransferStarted(Message m, DTNHost from, DTNHost to) {
        if (isWarmupID(m.getId())) return;
        checkPeriod();
        this.nrofStarted++;
    }

    @Override
    public void done() {
        write("\n=== LAPORAN AKHIR ===");
        writeReport(getSimTime());
        super.done();
    }
}