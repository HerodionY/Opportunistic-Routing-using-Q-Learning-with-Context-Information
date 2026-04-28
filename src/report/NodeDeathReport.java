package report;

import core.DTNHost;
import core.UpdateListener;
import routing.CCRouting;
import routing.EpidemicEnergyRouter;
import routing.ProphetEnergyRouter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Node Death Report — tracks when each energy-aware node's energy reaches 0.
 *
 * Compatible routers (auto-detected via instanceof):
 *   - CCRouting (ORQLCI): also tracks hard-gate entry event (≤4%)
 *   - EpidemicEnergyRouter
 *   - ProphetEnergyRouter
 *
 * Output format (TSV, easy to parse in Python/pandas):
 *   # sim_time  node_addr  router_type  max_energy  event
 *   3600.0      42         Epidemic     951.3        DEAD
 *   7200.0      17         ORQLCI       887.2        HARD_GATE   (CCRouting only)
 *   9800.0      17         ORQLCI       887.2        DEAD
 *
 * Config (all optional):
 *   NodeDeathReport.granularity = 60   # check interval in sim seconds (default: 60)
 *
 * Add to config:
 *   Report.reportN = NodeDeathReport
 */
public class NodeDeathReport extends Report implements UpdateListener {

    /** How often (sim seconds) to poll node energy levels. Default: 60s */
    public static final String GRANULARITY_S = "granularity";
    private static final int DEFAULT_GRANULARITY = 60;

    private final int granularity;
    private double lastUpdate = 0.0;

    /** Set of node addresses that have already been recorded as DEAD */
    private final Set<Integer> deadNodes = new HashSet<>();

    /**
     * Set of node addresses (CCRouting only) that have already been recorded
     * as entering the HARD_GATE zone (energy ≤ 4%).
     */
    private final Set<Integer> hardGateNodes = new HashSet<>();

    public NodeDeathReport() {
        super();
        int g = DEFAULT_GRANULARITY;
        try {
            g = getSettings().getInt(GRANULARITY_S);
        } catch (Exception e) { /* use default */ }
        this.granularity = g;

        init();
        // Write TSV header
        write("# Node Death / Hard-Gate Report");
        write("# sim_time\tnode_addr\trouter_type\tmax_energy\tenergy_ratio\tevent");
    }

    // =========================================================================
    // UpdateListener
    // =========================================================================

    @Override
    public void updated(List<DTNHost> hosts) {
        if (isWarmup()) return;

        double now = getSimTime();
        if (now - lastUpdate < granularity) return;
        lastUpdate = now;

        for (DTNHost host : hosts) {
            int addr = host.getAddress();
            Object router = host.getRouter();

            if (router instanceof CCRouting) {
                checkCCRouting(host, (CCRouting) router, addr, now);

            } else if (router instanceof EpidemicEnergyRouter) {
                checkGenericDeath(host, (EpidemicEnergyRouter) router, addr, now, "Epidemic");

            } else if (router instanceof ProphetEnergyRouter) {
                checkGenericDeath(host, (ProphetEnergyRouter) router, addr, now, "Prophet");
            }
            // Nodes without energy model are ignored
        }
    }

    // =========================================================================
    // Per-router checks
    // =========================================================================

    /**
     * CCRouting: track both HARD_GATE entry (≤4%) and full DEAD (=0%).
     */
    private void checkCCRouting(DTNHost host, CCRouting r, int addr, double now) {
        double maxE   = r.getMaxEnergy();
        double currE  = r.getCurrentEnergy();

        if (maxE <= 0) return; // not yet initialized

        double ratio = currE / maxE;

        // Hard gate event (first time ≤ 4% — CCRouting stops accepting new msgs)
        if (!hardGateNodes.contains(addr) && r.isInHardGate()) {
            hardGateNodes.add(addr);
            write(String.format("%.1f\t%d\tORQLCI\t%.2f\t%.4f\tHARD_GATE",
                    now, addr, maxE, ratio));
        }

        // Death event (energy = 0)
        if (!deadNodes.contains(addr) && currE <= 0) {
            deadNodes.add(addr);
            write(String.format("%.1f\t%d\tORQLCI\t%.2f\t%.4f\tDEAD",
                    now, addr, maxE, ratio));
        }
    }

    /**
     * EpidemicEnergyRouter / ProphetEnergyRouter: track DEAD only (no hard gate).
     */
    private void checkGenericDeath(DTNHost host, EpidemicEnergyRouter r,
                                    int addr, double now, String routerType) {
        double maxE  = r.getMaxEnergy();
        double currE = r.getCurrentEnergy();

        if (maxE <= 0) return;
        if (deadNodes.contains(addr)) return;

        if (currE <= 0) {
            deadNodes.add(addr);
            write(String.format("%.1f\t%d\t%s\t%.2f\t%.4f\tDEAD",
                    now, addr, routerType, maxE, 0.0));
        }
    }

    private void checkGenericDeath(DTNHost host, ProphetEnergyRouter r,
                                    int addr, double now, String routerType) {
        double maxE  = r.getMaxEnergy();
        double currE = r.getCurrentEnergy();

        if (maxE <= 0) return;
        if (deadNodes.contains(addr)) return;

        if (currE <= 0) {
            deadNodes.add(addr);
            write(String.format("%.1f\t%d\t%s\t%.2f\t%.4f\tDEAD",
                    now, addr, routerType, maxE, 0.0));
        }
    }

    // =========================================================================
    // Summary on done()
    // =========================================================================

    @Override
    public void done() {
        write("");
        write("# ===== SUMMARY =====");
        write("# total_dead_nodes: " + deadNodes.size());
        write("# total_hard_gate_nodes (ORQLCI only): " + hardGateNodes.size());
        super.done();
    }
}
