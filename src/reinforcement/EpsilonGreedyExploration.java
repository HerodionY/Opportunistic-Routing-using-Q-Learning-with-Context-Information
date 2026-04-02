// Catalano Machine Learning Library
// The Catalano Framework
//
// Copyright © Diego Catalano, 2012-2016
// diego.catalano at live.com
//
// Copyright © Andrew Kirillov, 2007-2008
// andrew.kirillov@gmail.com
//
//    This library is free software; you can redistribute it and/or
//    modify it under the terms of the GNU Lesser General Public
//    License as published by the Free Software Foundation; either
//    version 2.1 of the License, or (at your option) any later version.
//
//    This library is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
//    Lesser General Public License for more details.
//
//    You should have received a copy of the GNU Lesser General Public
//    License along with this library; if not, write to the Free Software
//    Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
//

package reinforcement;

import core.DTNHost;
import core.Tuple;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * <para>The class implements epsilon greedy exploration policy. Acording to the policy,
 * the best action is chosen with probability <b>1-epsilon</b>. Otherwise,
 * with probability <b>epsilon</b>, any other action, except the best one, is
 * chosen randomly.</para>
 * 
 * <para> According to the policy, the epsilon value is known also as exploration rate. </para>
 * @author Diego Catalano
 */
public class EpsilonGreedyExploration implements IExplorationPolicy{
    private double epsilon;
    
    private Random r = new Random();

    /**
     * Initializes a new instance of the EpsilonGreedyExploration class.
     * @param epsilon Epsilon value (exploration rate).
     */
    public EpsilonGreedyExploration(double epsilon) {
        this.epsilon = epsilon;
    }

    /**
     * Get Episilon value.
     * @return Return Epsilon value.
     */
    public double getEpsilon() {
        return epsilon;
    }

    /**
     * The value determines the amount of exploration driven by the policy.
     * If the value is high, then the policy drives more to exploration - choosing random
     * action, which excludes the best one. If the value is low, then the policy is more
     * greedy - choosing the beat so far action.
     * 
     * @param epsilon Epsilon value (exploration rate), [0, 1].
     */
    public void setEpsilon(double epsilon) {
        this.epsilon = Math.max( 0.0, Math.min( 1.0, epsilon ) );
    }
    
    /**
     * The method chooses an action depending on the provided estimates. The
     * estimates can be any sort of estimate, which values usefulness of the action
     * (expected summary reward, discounted reward, etc).
     * 
     * @param actionEstimates Action Estimates.
     * @return Return Selected actions.
     */
    @Override
    public int ChooseAction(double[] actionEstimates, Map<Integer, Tuple<DTNHost, List  <Integer>>> waitForReward, boolean isWaitingReward){
        int actionsCount = actionEstimates.length;

        // filter candidate actions based on pending status
        List<Integer> candidates = new ArrayList<Integer>(actionsCount);
        for (int i = 0; i < actionsCount; i++) {
            boolean pending = false;
            if (waitForReward != null && waitForReward.get(i) != null &&
                    waitForReward.get(i).getValue() != null &&
                    !waitForReward.get(i).getValue().isEmpty()) {
                pending = true;
            }
            if (isWaitingReward) {
                if (pending) {
                    candidates.add(i);
                }
            } else {
                if (!pending) {
                    candidates.add(i);
                }
            }
        }
        if (candidates.isEmpty()) {
            for (int i = 0; i < actionsCount; i++) {
                candidates.add(i);
            }
        }

        // find the best action (greedy) among candidates
        int greedyAction = candidates.get(0);
        double maxReward = actionEstimates[greedyAction];
        for (int idx = 1; idx < candidates.size(); idx++) {
            int i = candidates.get(idx);
            if (actionEstimates[i] > maxReward) {
                maxReward = actionEstimates[i];
                greedyAction = i;
            }
        }

        // try to do exploration
        if ( r.nextDouble( ) < epsilon && candidates.size() > 1 ) {
            int randIndex = r.nextInt(candidates.size() - 1);
            int randomAction = candidates.get(randIndex);
            if (randomAction == greedyAction) {
                randomAction = candidates.get(candidates.size() - 1);
            }
            return randomAction;
        }

        return greedyAction;
    }
    
}
