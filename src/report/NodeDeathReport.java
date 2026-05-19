package report;

import core.DTNHost;
import core.UpdateListener;
import routing.CCRouting;
import routing.CCRoutingWithoutEnergyContext;
import routing.EpidemicEnergyRouter;
import routing.ProphetEnergyRouter;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class NodeDeathReport extends Report implements UpdateListener {

    public static final String GRANULARITY_S = "granularity";
    private static final int DEFAULT_GRANULARITY = 60;

    private final int granularity;
    private double lastUpdate = 0.0;

    private final Set<Integer> deadNodes = new HashSet<>();

    public NodeDeathReport() {
        super();
        int g = DEFAULT_GRANULARITY;
        try {
            g = getSettings().getInt(GRANULARITY_S);
        } catch (Exception e) {
        }
        this.granularity = g;

        init();
        write("# Node Death Report");
        write("# sim_time\tnode_addr\trouter_type\tmax_energy\tenergy_ratio\tevent");
    }

    @Override
    public void updated(List<DTNHost> hosts) {
        if (isWarmup())
            return;

        double now = getSimTime();
        if (now - lastUpdate < granularity)
            return;
        lastUpdate = now;

        for (DTNHost host : hosts) {
            int addr = host.getAddress();
            Object router = host.getRouter();

            if (router instanceof CCRouting) {
                checkCCRouting(host, (CCRouting) router, addr, now);

            } else if (router instanceof CCRoutingWithoutEnergyContext) {
                checkCCRoutingWithoutEnergy(host, (CCRoutingWithoutEnergyContext) router, addr, now);

            } else if (router instanceof EpidemicEnergyRouter) {
                checkGenericDeath(host, (EpidemicEnergyRouter) router, addr, now, "Epidemic");

            } else if (router instanceof ProphetEnergyRouter) {
                checkGenericDeath(host, (ProphetEnergyRouter) router, addr, now, "Prophet");
            }
        }
    }

    private void checkCCRouting(DTNHost host, CCRouting r, int addr, double now) {
        double maxE = r.getMaxEnergy();
        double currE = r.getCurrentEnergy();

        if (maxE <= 0)
            return;

        double ratio = currE / maxE;

        if (!deadNodes.contains(addr) && currE <= 0) {
            deadNodes.add(addr);
            write(String.format("%.1f\t%d\tORQLCI\t%.2f\t%.4f\tDEAD",
                    now, addr, maxE, ratio));
        }
    }

    private void checkCCRoutingWithoutEnergy(DTNHost host, CCRoutingWithoutEnergyContext r,
            int addr, double now) {
        double maxE = r.getMaxEnergy();
        double currE = r.getCurrentEnergy();

        if (maxE <= 0)
            return;

        double ratio = currE / maxE;

        if (!deadNodes.contains(addr) && currE <= 0) {
            deadNodes.add(addr);
            write(String.format("%.1f\t%d\tORQLCI_NoEnergyCtx\t%.2f\t%.4f\tDEAD",
                    now, addr, maxE, ratio));
        }
    }

    private void checkGenericDeath(DTNHost host, EpidemicEnergyRouter r,
            int addr, double now, String routerType) {
        double maxE = r.getMaxEnergy();
        double currE = r.getCurrentEnergy();

        if (maxE <= 0)
            return;
        if (deadNodes.contains(addr))
            return;

        if (currE <= 0) {
            deadNodes.add(addr);
            write(String.format("%.1f\t%d\t%s\t%.2f\t%.4f\tDEAD",
                    now, addr, routerType, maxE, 0.0));
        }
    }

    private void checkGenericDeath(DTNHost host, ProphetEnergyRouter r,
            int addr, double now, String routerType) {
        double maxE = r.getMaxEnergy();
        double currE = r.getCurrentEnergy();

        if (maxE <= 0)
            return;
        if (deadNodes.contains(addr))
            return;

        if (currE <= 0) {
            deadNodes.add(addr);
            write(String.format("%.1f\t%d\t%s\t%.2f\t%.4f\tDEAD",
                    now, addr, routerType, maxE, 0.0));
        }
    }

    @Override
    public void done() {
        write("");
        write("# ===== SUMMARY =====");
        write("# total_dead_nodes: " + deadNodes.size());
        super.done();
    }
}
