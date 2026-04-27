package routing;

import core.*;
import java.util.*;

/**
 * Energy-aware variant of EpidemicRouter for fair comparison with CCRouting (ORQLCI).
 *
 * Routing logic: IDENTICAL to EpidemicRouter (flood all messages to all connections).
 * Energy model: IDENTICAL drain formula to CCRouting:
 *   - Scan energy: deducted every scanInterval seconds
 *   - Transmit energy: proportional to active transmitting connections x time
 *   - Receive energy: proportional to active receiving connections x time
 *   - Initial energy: random [initialEnergy-200, initialEnergy], seed = address+12345
 *
 * NO hard gate, NO soft zone — nodes route freely until energy reaches 0.
 * When energy = 0: node can no longer initiate or accept new transfers (natural death).
 *
 * Config namespace: EpidemicEnergyRouter
 *   EpidemicEnergyRouter.initialEnergy  = 1000
 *   EpidemicEnergyRouter.scanEnergy     = 0.1
 *   EpidemicEnergyRouter.transmitEnergy = 0.5
 *   EpidemicEnergyRouter.receiveEnergy  = 0.1
 */
public class EpidemicEnergyRouter extends EpidemicRouter {

    private static final String NS = "EpidemicEnergyRouter";
    private static final String INITIAL_ENERGY_S  = "initialEnergy";
    private static final String SCAN_ENERGY_S     = "scanEnergy";
    private static final String TRANSMIT_ENERGY_S = "transmitEnergy";
    private static final String RECEIVE_ENERGY_S  = "receiveEnergy";

    private static final double INITIAL_ENERGY_DEFAULT  = 1000.0;
    private static final double SCAN_ENERGY_DEFAULT     = 0.1;
    private static final double TRANSMIT_ENERGY_DEFAULT = 0.5;
    private static final double RECEIVE_ENERGY_DEFAULT  = 0.1;

    private double initialEnergyConfig;
    private double scanEnergy;
    private double transmitEnergy;
    private double receiveEnergy;

    /** Sentinel: -1 means not yet initialized (set in init() after host is available). */
    private double maxEnergy     = -1.0;
    private double currentEnergy = -1.0;

    private double cachedScanInterval    = 120.0;
    private double lastScanEnergyUpdate  = 0.0;
    private double lastEnergyUpdate      = 0.0;

    // =========================================================================
    // CONSTRUCTORS
    // =========================================================================

    public EpidemicEnergyRouter(Settings s) {
        super(s);
        Settings ns = new Settings(NS);

        this.initialEnergyConfig = ns.contains(INITIAL_ENERGY_S)
                ? ns.getDouble(INITIAL_ENERGY_S) : INITIAL_ENERGY_DEFAULT;
        this.scanEnergy = ns.contains(SCAN_ENERGY_S)
                ? ns.getDouble(SCAN_ENERGY_S) : SCAN_ENERGY_DEFAULT;
        this.transmitEnergy = ns.contains(TRANSMIT_ENERGY_S)
                ? ns.getDouble(TRANSMIT_ENERGY_S) : TRANSMIT_ENERGY_DEFAULT;
        this.receiveEnergy = ns.contains(RECEIVE_ENERGY_S)
                ? ns.getDouble(RECEIVE_ENERGY_S) : RECEIVE_ENERGY_DEFAULT;

        this.cachedScanInterval = readScanInterval();
    }

    protected EpidemicEnergyRouter(EpidemicEnergyRouter r) {
        super(r);
        this.initialEnergyConfig = r.initialEnergyConfig;
        this.scanEnergy          = r.scanEnergy;
        this.transmitEnergy      = r.transmitEnergy;
        this.receiveEnergy       = r.receiveEnergy;
        this.cachedScanInterval  = r.cachedScanInterval;
        // Energy initialized later in init()
        this.maxEnergy     = -1.0;
        this.currentEnergy = -1.0;
        this.lastScanEnergyUpdate = 0.0;
        this.lastEnergyUpdate     = 0.0;
    }

    // =========================================================================
    // INIT — Energy randomized per-node after host is available
    // =========================================================================

    @Override
    public void init(DTNHost host, List<MessageListener> mListeners) {
        super.init(host, mListeners);
        if (maxEnergy < 0) {
            double minEnergy = Math.max(0, initialEnergyConfig - 200.0);
            // Deterministic per-node seed, same formula as CCRouting
            Random nodeRng = new Random(host.getAddress() + 12345L);
            this.maxEnergy     = minEnergy + nodeRng.nextDouble() * (initialEnergyConfig - minEnergy);
            this.currentEnergy = this.maxEnergy;
        }
    }

    // =========================================================================
    // ENERGY DRAIN (identical model to CCRouting)
    // =========================================================================

    private double readScanInterval() {
        try {
            Settings iface = new Settings("btInterface");
            if (iface.contains("scanInterval")) {
                return iface.getDouble("scanInterval");
            }
        } catch (Exception e) { /* use default */ }
        return 120.0;
    }

    private void consumeScanEnergy() {
        if (cachedScanInterval <= 0) return;
        double now = SimClock.getTime();
        while (now >= lastScanEnergyUpdate + cachedScanInterval) {
            currentEnergy = Math.max(0.0, currentEnergy - scanEnergy);
            lastScanEnergyUpdate += cachedScanInterval;
        }
    }

    private void consumeTransferEnergy() {
        double now = SimClock.getTime();
        double timeDiff = now - lastEnergyUpdate;
        if (timeDiff <= 0) {
            lastEnergyUpdate = now;
            return;
        }
        int numTransmitting = 0;
        int numReceiving    = 0;
        for (Connection con : getConnections()) {
            if (!con.isUp()) continue;
            Message msg = con.getMessage();
            if (msg == null) continue;
            if (con.isInitiator(getHost())) numTransmitting++;
            else numReceiving++;
        }
        if (numTransmitting > 0) {
            currentEnergy = Math.max(0.0, currentEnergy - transmitEnergy * timeDiff * numTransmitting);
        }
        if (numReceiving > 0) {
            currentEnergy = Math.max(0.0, currentEnergy - receiveEnergy * timeDiff * numReceiving);
        }
        lastEnergyUpdate = now;
    }

    // =========================================================================
    // OVERRIDES — Only energy-related, NO routing logic changes
    // =========================================================================

    /**
     * Deny receiving new messages when energy is fully depleted (natural death).
     * No artificial threshold — only triggers at energy == 0.
     */
    @Override
    protected int checkReceiving(Message m) {
        if (maxEnergy > 0 && currentEnergy <= 0) {
            return DENIED_UNSPECIFIED;
        }
        return super.checkReceiving(m);
    }

    @Override
    public void update() {
        // Always drain energy first (even while transferring)
        if (maxEnergy > 0) {
            consumeScanEnergy();
            consumeTransferEnergy();
        }
        // Natural death: skip routing when fully depleted
        if (maxEnergy > 0 && currentEnergy <= 0) {
            return;
        }
        // Epidemic routing logic — completely unchanged
        super.update();
    }

    @Override
    public EpidemicEnergyRouter replicate() {
        return new EpidemicEnergyRouter(this);
    }

    // =========================================================================
    // GETTERS
    // =========================================================================

    public double getCurrentEnergy() { return currentEnergy; }
    public double getMaxEnergy()     { return maxEnergy; }
    public double getEnergyRatio()   { return maxEnergy > 0 ? currentEnergy / maxEnergy : 1.0; }

    @Override
    public String toString() {
        return super.toString() + String.format(" [E=%.0f/%.0f(%.0f%%)]",
                currentEnergy, maxEnergy, getEnergyRatio() * 100);
    }
}
