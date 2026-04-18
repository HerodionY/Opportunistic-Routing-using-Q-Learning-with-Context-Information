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
    private static double tempTDTargetSum = 0;
    private static double tempQValueSum = 0;
    private static int tempUpdateCount = 0;

    private static double[] stateRewardSum = new double[3];
    private static int[] stateUpdateCount = new int[3];

    private static double totalCumulativeReward = 0;

    public RewardTimeReport() {
        Settings s = getSettings();
        if (s.contains(INTERVAL_SETTING)) {
            interval = s.getInt(INTERVAL_SETTING);
        } else {
            interval = DEFAULT_INTERVAL;
        }
        nextReportTime = interval;

        // Header baru: menjelaskan trend reward, TD target, Q-value, dan reward tiap state
        write("time avg_reward avg_td_target avg_q_v s0_reward s1_reward s2_reward total_reward updates");
    }

    public static void addReward(int state, double reward, double tdTarget, double qValue) {
        tempRewardSum += reward;
        tempTDTargetSum += tdTarget;
        tempQValueSum += qValue;
        tempUpdateCount++;

        if (state >= 0 && state < stateRewardSum.length) {
            stateRewardSum[state] += reward;
            stateUpdateCount[state]++;
        }

        totalCumulativeReward += reward;
    }

    @Override
    public void updated(List<DTNHost> hosts) {
        if (SimClock.getTime() >= nextReportTime) {
            double avgReward = (tempUpdateCount > 0) ? (tempRewardSum / tempUpdateCount) : 0;
            double avgTDTarget = (tempUpdateCount > 0) ? (tempTDTargetSum / tempUpdateCount) : 0;
            double avgQV = (tempUpdateCount > 0) ? (tempQValueSum / tempUpdateCount) : 0;

            String s0Reward = (stateUpdateCount[0] > 0) ? format(stateRewardSum[0] / stateUpdateCount[0]) : "0.0000";
            String s1Reward = (stateUpdateCount[1] > 0) ? format(stateRewardSum[1] / stateUpdateCount[1]) : "0.0000";
            String s2Reward = (stateUpdateCount[2] > 0) ? format(stateRewardSum[2] / stateUpdateCount[2]) : "0.0000";

            write(format(SimClock.getTime()) + " " +
                    format(avgReward) + " " +
                    format(avgTDTarget) + " " +
                    format(avgQV) + " " +
                    s0Reward + " " +
                    s1Reward + " " +
                    s2Reward + " " +
                    format(totalCumulativeReward) + " " +
                    tempUpdateCount);

            // Reset temp counters
            tempRewardSum = 0;
            tempTDTargetSum = 0;
            tempQValueSum = 0;
            tempUpdateCount = 0;
            for (int i = 0; i < stateRewardSum.length; i++) {
                stateRewardSum[i] = 0;
                stateUpdateCount[i] = 0;
            }

            nextReportTime += interval;
        }
    }
}