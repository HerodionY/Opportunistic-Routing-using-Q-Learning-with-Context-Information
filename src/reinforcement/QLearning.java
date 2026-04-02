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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import routing.QLearningRouter;

/**
 * The class provides implementation of Q-Learning algorithm, known as
 * off-policy Temporal Difference control.
 * 
 * @author Diego Catalano
 */
public class QLearning{
    
    // amount of possible states
    private int states;
    // amount of possible actions
    private int actions;
    // q-values
    // private double[][] qvalues;
    // === TAMBAHAN ORQLCI: Q-TABLE BERBASIS TUJUAN (DI-COMMENT) ===
    // Menggunakan Map untuk menyimpan Q-Table (state x action) khusus untuk setiap node tujuan (destination)
    private Map<Integer, double[][]> qvalues;
    // ==============================================================
    
    // exploration policy
    private IExplorationPolicy explorationPolicy;

    // discount factor
    private double discountFactor = 0.0;
    private double gamma1 = 100.0;
    private double gamma2 = 0.01;

    // learning rate
    // private double learningRate = 0.25;
    private double learningRate = 0.0;

    /**
     *  Initializes a new instance of the QLearning class.
     * @param states Amount of possible states.
     * @param actions Amount of possible actions.
     * @param explorationPolicy Exploration policy.
     * @param randomize Randomize action estimates or not.
     */
    public QLearning( int states, int actions, IExplorationPolicy explorationPolicy, boolean randomize ){
        this.states  = states;
        this.actions = actions;
        this.explorationPolicy = explorationPolicy;
        this.qvalues = new HashMap<>();

        // create Q-array
        // qvalues = new double[states][];
        // === TAMBAHAN ORQLCI: INISIALISASI Q-TABLE MAP (DI-COMMENT) ===
        qvalues = new HashMap<>();
        // ==============================================================
        // for ( int i = 0; i < states; i++ ){
        //     qvalues[i] = new double[actions];
        // }
    }

    // === FUNGSI BARU: Untuk membuat tabel Q khusus satu destination ===
    private void initDestinationIfNeeded(int destination) {
        if (!qvalues.containsKey(destination)) {
            double[][] newQTable = new double[states][actions];
            Random r = new Random();
            for ( int i = 0; i < states; i++ ){
                for ( int j = 0; j < actions; j++ ){
                    newQTable[i][j] = r.nextDouble() / 10.0; // Randomize awal
                }
            }
            qvalues.put(destination, newQTable);
        }
    }

    /**
     * Amount of possible states.
     * @return States
     */
    public int getStates() {
        return states;
    }

    /**
     * Amount of possible actions.
     * @return Actions
     */
    public int getActions() {
        return actions;
    }

    /**
     * Exploration policy.
     * @return Exploration Policy
     */
    public IExplorationPolicy getExplorationPolicy() {
        return explorationPolicy;
    }

    /**
     * Policy, which is used to select actions.
     * @param explorationPolicy Exploration Policy
     */
    public void setExplorationPolicy(IExplorationPolicy explorationPolicy) {
        this.explorationPolicy = explorationPolicy;
    }

    /**
     * Discount factor.
     * @return Discount Factor
     */
    public double getDiscountFactor() {
        return discountFactor;
    }

    /**
     * Discount factor.
     * @param discountFactor Discount Factor
     */
    public void setDiscountFactor(double discountFactor) {
        this.discountFactor = discountFactor;
    }

    /**
     * Learning rate.
     * @return Learning Rate
     */
    public double getLearningRate() {
        return learningRate;
    }

    /**
     * Learning rate.
     * @param learningRate Learning Rate
     */
    public void setLearningRate(double learningRate) {
        this.learningRate = learningRate;
    }

    public void setLearningRate(int n, double h) {
        this.learningRate = h / (double)n;
    }

    public void setDiscountFactorDynamic(double baseGamma, double bufferFactor, double energyFactor) {
        double g = baseGamma * bufferFactor * energyFactor;
        
        // ensure g is in reasonable range
        if (g < 0) g = 0;
        if (g > 1) g = 1;

        this.discountFactor = g;
    }
    
    /**
     * Get next action from the specified state.
     * @param state Current state to get an action for.
     * @return Returns the action for the state.
     */
    public int GetAction(int destination, int state, Map<Integer, Tuple<DTNHost, List<Integer>>> waitForReward,  boolean isWaitingReward ){
        
        // === TAMBAHAN ORQLCI: AMBIL Q-VALUES BERDASARKAN DESTINATION (DI-COMMENT) ===
        initDestinationIfNeeded(destination);
        return explorationPolicy.ChooseAction( qvalues.get(destination)[state], waitForReward, isWaitingReward );
        // ==============================================================================
        
        // return explorationPolicy.ChooseAction( qvalues[state], waitForReward, isWaitingReward );
    }
    
    /**
     * Update Q-function's value for the previous state-action pair.
     * @param previousState Previous state.
     * @param action Action, which leads from previous to the next state.
     * @param reward Reward value, received by taking specified action from previous state.
     * @param nextState Next state.
     */
    public void UpdateState(int destination, int previousState, int action, double reward, int nextState, QLearningRouter router, DTNHost pendingHost ){
        initDestinationIfNeeded(destination);
        double[][] table = qvalues.get(destination);

        // find maximum expected summary reward from the next state
        double[] nextActionEstimations = table[nextState];
        double maxNextExpectedReward = nextActionEstimations[0];

        for ( int i = 1; i < actions; i++ ){
                if ( nextActionEstimations[i] > maxNextExpectedReward )
                        maxNextExpectedReward = nextActionEstimations[i];
        }

        // previous state's action estimations
        double[] previousActionEstimations = table[previousState];
        
        // update expected summary reward of the previous state
        previousActionEstimations[action] *= (1.0 - learningRate);
        previousActionEstimations[action] += (learningRate * (reward + discountFactor * maxNextExpectedReward));

        // update wait for reward state
        Map<Integer, Tuple<DTNHost, List<Integer>>> waitForReward = router.getMapWaitForReward();
        waitForReward.put(previousState, new Tuple<>(pendingHost, new ArrayList<Integer>()));
    }

    public double getQV(int destination, int state, int action) {
        // === TAMBAHAN ORQLCI: GET Q-VALUE BERDASARKAN DESTINATION (DI-COMMENT) ===
        initDestinationIfNeeded(destination);
        return qvalues.get(destination)[state][action];
    }
}
