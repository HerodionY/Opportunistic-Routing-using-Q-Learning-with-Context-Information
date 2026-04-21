package routing;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import core.DTNHost;
import core.Settings;
import core.Tuple;
import core.Connection;

/**
 * A standard Q-Learning router without social or congestion context information.
 * It uses the base QLearningRouter functionality for basic node encounter learning.
 */
public class StandardQLearningRouter extends QLearningRouter {
    private Map<Integer, Tuple<DTNHost, List<Integer>>> waitForReward;

    public StandardQLearningRouter(Settings s) {
        super(s);
        this.waitForReward = new HashMap<>();
    }

    protected StandardQLearningRouter(StandardQLearningRouter r) {
        super(r);
        this.waitForReward = new HashMap<>();
    }

    @Override
    public StandardQLearningRouter replicate() {
        return new StandardQLearningRouter(this);
    }

    public Map<Integer, Tuple<DTNHost, List<Integer>>> getMapWaitForReward() {
        return this.waitForReward;
    }

    @Override
    public void changedConnection(Connection con) {
        super.changedConnection(con);
        DTNHost otherNode = con.getOtherNode(getHost());
        if (con.isUp()) {
            if(!this.waitForReward.containsKey(otherNode.getAddress())) {
                this.waitForReward.put(otherNode.getAddress(), new Tuple<>(otherNode, new ArrayList<>()));
            }
        }
    }
}
