package report;

import core.DTNHost;
import core.Settings;
import core.UpdateListener;
import java.util.List;
import routing.CCRouting;

/**
 * Reports number of dead nodes (energy <= 0) over time.
 */
public class DeadNodesReport extends Report implements UpdateListener {

    public static final String GRANULARITY = "granularity";
    private final int granularity;
    private double lastUpdate;

    public DeadNodesReport() {
        Settings settings = getSettings();
        this.granularity = settings.contains(GRANULARITY) ? settings.getInt(GRANULARITY) : 60;
        this.lastUpdate = 0;
        init();
        write("# time dead_nodes alive_nodes tracked_nodes dead_ratio_pct");
    }

    @Override
    public void updated(List<DTNHost> hosts) {
        if (isWarmup()) {
            return;
        }

        double simTime = getSimTime();
        if (simTime - lastUpdate < granularity) {
            return;
        }
        this.lastUpdate = simTime - simTime % granularity;

        int dead = 0;
        int tracked = 0;
        for (DTNHost h : hosts) {
            Object energyObj = h.getComBus().getProperty(CCRouting.ENERGY_VALUE_ID);
            if (!(energyObj instanceof Double)) {
                continue;
            }
            tracked++;
            double energy = (Double) energyObj;
            if (energy <= 0) {
                dead++;
            }
        }

        int alive = tracked - dead;
        double deadRatio = tracked > 0 ? ((double) dead / (double) tracked) * 100.0 : 0.0;
        write((int) simTime + " " + dead + " " + alive + " " + tracked + " " + format(deadRatio));
    }
}
