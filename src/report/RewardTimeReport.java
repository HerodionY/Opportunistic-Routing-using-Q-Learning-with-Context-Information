package report;

import java.util.ArrayList;
import java.util.List;
import core.UpdateListener;
import core.DTNHost;
import core.SimClock;
import core.Settings;

public class RewardTimeReport extends Report implements UpdateListener {
    public static final String INTERVAL_SETTING = "interval";
    public static final int DEFAULT_INTERVAL = 1000;

    private int interval;
    private double nextReportTime;

    private static double tempRewardSum = 0;
    private static int tempUpdateCount = 0;
    private static double totalCumulativeReward = 0;

    public RewardTimeReport() {
        Settings s = getSettings();
        if (s.contains(INTERVAL_SETTING)) {
            interval = s.getInt(INTERVAL_SETTING);
        } else {
            interval = DEFAULT_INTERVAL;
        }
        nextReportTime = interval;

        write("time avg_reward cumulative_reward");
    }

    public static void addReward(double reward) {
        tempRewardSum += reward;
        totalCumulativeReward += reward;
        tempUpdateCount++;
    }

    @Override
    public void updated(List<DTNHost> hosts) {
        if (SimClock.getTime() >= nextReportTime) {
            double avgReward = (tempUpdateCount > 0) ? (tempRewardSum / tempUpdateCount) : 0;
            write(format(SimClock.getTime()) + " " + format(avgReward) + " " + format(totalCumulativeReward));

            tempRewardSum = 0;
            tempUpdateCount = 0;
            nextReportTime += interval;
        }
    }
}