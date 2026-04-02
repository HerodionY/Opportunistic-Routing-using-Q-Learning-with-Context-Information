/* 
 * Dead nodes report based on energy level.
 */
package report;

import java.util.ArrayList;
import java.util.List;

import core.DTNHost;
import core.Settings;
import core.UpdateListener;
import routing.EnergyAwareRouter;

/**
 * Reports the number of dead nodes (energy <= threshold) at a fixed interval.
 */
public class DeadNodesReport extends Report implements UpdateListener {
	/** Reporting granularity -setting id ({@value}). */
	public static final String GRANULARITY = "granularity";
	/** Optional: list dead node addresses -setting id ({@value}). */
	public static final String LIST_NODES = "listNodes";
	/** Energy threshold to consider a node dead (absolute value). */
	public static final String DEAD_THRESHOLD = "deadThreshold";

	protected final int granularity;
	protected final boolean listNodes;
	protected final double deadThreshold;
	protected double lastUpdate;

	public DeadNodesReport() {
		Settings settings = getSettings();
		this.lastUpdate = 0;
		this.granularity = settings.getInt(GRANULARITY);
		if (settings.contains(LIST_NODES)) {
			this.listNodes = settings.getBoolean(LIST_NODES);
		} else {
			this.listNodes = false;
		}
		if (settings.contains(DEAD_THRESHOLD)) {
			this.deadThreshold = settings.getDouble(DEAD_THRESHOLD);
		} else {
			this.deadThreshold = 0.0;
		}
		init();
	}

	@Override
	public void updated(List<DTNHost> hosts) {
		double simTime = getSimTime();
		if (isWarmup()) {
			return;
		}
		if (simTime - lastUpdate >= granularity) {
			createSnapshot(hosts);
			this.lastUpdate = simTime - simTime % granularity;
		}
	}

	private void createSnapshot(List<DTNHost> hosts) {
		int dead = 0;
		List<Integer> deadNodes = listNodes ? new ArrayList<Integer>() : null;

		for (DTNHost h : hosts) {
			Object value = h.getComBus().getProperty(EnergyAwareRouter.ENERGY_VALUE_ID);
			if (!(value instanceof Double)) {
				continue; /* node is not energy-aware */
			}
			double energy = (Double) value;
			if (energy <= deadThreshold) {
				dead++;
				if (listNodes) {
					deadNodes.add(h.getAddress());
				}
			}
		}

		int total = hosts.size();
		double ratio = (total > 0) ? ((double) dead / (double) total) : 0.0;
		String line = "[" + (int) getSimTime() + "] dead=" + dead + "/" + total +
				" ratio=" + format(ratio);
		if (listNodes) {
			line += " nodes=" + join(deadNodes);
		}
		write(line);
	}

	private String join(List<Integer> values) {
		if (values == null || values.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < values.size(); i++) {
			if (i > 0) {
				sb.append(",");
			}
			sb.append(values.get(i));
		}
		return sb.toString();
	}
}
