package reinforcement;

import core.DTNHost;
import core.Tuple;
import java.util.*;
import routing.QLearningRouter;

/**
 * Implementation of Q-Learning algorithm optimized for ORQLCI.
 * Features: Multi-destination Q-Tables, Dynamic Discounting (BF),
 * Future Reward Filtering (PROPHET), and Q-Table Ageing.
 * * @author Chornael Damar Kesuma (Refined version)
 */
public class QLearning {

    private int states;
    private int actions;

    // Q-Table structure: Map<DestinationAddress, double[State][Action]>
    private Map<Integer, double[][]> qvalues;

    private IExplorationPolicy explorationPolicy;

    // Parameters for ORQLCI Logic
    private double discountFactor = 0.0; // Acts as gamma_d
    private double learningRate = 0.0; // Acts as alpha

    // Ageing Factor for Q-Values (Equation usually found in DTN RL papers)
    // Helps the agent to forget old, invalid routing information.
    private static final double Q_AGEING_FACTOR = 0.98;

    /**
     * Initializes the Q-Learning engine.
     * 
     * @param states            Total number of nodes/states.
     * @param actions           Total number of possible relay actions.
     * @param explorationPolicy The Epsilon-Greedy policy.
     * @param randomize         Whether to initialize with small random values.
     */
    public QLearning(int states, int actions, IExplorationPolicy explorationPolicy, boolean randomize) {
        this.states = states;
        this.actions = actions;
        this.explorationPolicy = explorationPolicy;
        this.qvalues = new HashMap<>();
    }

    /**
     * Lazy initialization for destination-specific Q-Tables.
     * 
     * @param destination The address of the message destination.
     */
    private void initDestinationIfNeeded(int destination) {
        if (!qvalues.containsKey(destination)) {
            double[][] newQTable = new double[states][actions];
            Random r = new Random();
            for (int i = 0; i < states; i++) {
                for (int j = 0; j < actions; j++) {
                    // Initialize with small random values to encourage early exploration
                    newQTable[i][j] = r.nextDouble() / 100.0;
                }
            }
            qvalues.put(destination, newQTable);
        }
    }

    /**
     * Sets the dynamic learning rate (Alpha) based on visit frequency.
     * Faster learning for new paths, stable for frequent paths.
     */
    public void setLearningRate(double visitCount, double coeff) {
        if (visitCount <= 0)
            visitCount = 1;
        this.learningRate = Math.min(1.0, coeff / visitCount);
    }

    /**
     * Implements Equation (7): gamma_d = gamma * BF_x
     * Adjusts the weight of future rewards based on neighbor's buffer health.
     */
    public void setDiscountFactorDynamic(double baseGamma, double bufferFactor) {
        this.discountFactor = Math.max(0.0, Math.min(1.0, baseGamma * bufferFactor));
    }

    /**
     * Implementation of Equation (10): max Q' = max(Q_d(x,y)) * P(x,y)
     * This method is called by the current node to get a filtered "promise"
     * from the neighbor node about its routing capability.
     * * @param destination The target destination.
     * 
     * @param pEncounter The encounter probability P(x,y) from PROPHET.
     * @return The weighted maximum future reward.
     */
    public double getNeighborMaxQPrime(int destination, double pEncounter) {
        initDestinationIfNeeded(destination);
        double[][] table = qvalues.get(destination);
        double maxQValue = 0.0;

        // Search for the best action in the neighbor's table
        // In DTN, state is often simplified to 0 (local knowledge)
        for (int s = 0; s < states; s++) {
            for (int a = 0; a < actions; a++) {
                if (table[s][a] > maxQValue) {
                    maxQValue = table[s][a];
                }
            }
        }

        // Multiply by PROPHET probability to get the 'Realistic' future value
        return maxQValue * pEncounter;
    }

    /**
     * Implementation of Equation (5) and (9): Q-Value Update Rule.
     * Q_new = (1-alpha)*Q_old + alpha * [Reward + gamma_d * max_Q_prime]
     * * @param destination Destination node address.
     * 
     * @param previousState     Usually the current node's internal state.
     * @param action            The relay node address chosen.
     * @param reward            1.0 if success, 0.0 otherwise.
     * @param neighborMaxQPrime The value from Equation (10).
     * @param router            The router instance for metadata sync.
     * @param pendingHost       The host we just interacted with.
     */
    public void UpdateState(int destination, int previousState, int action, double reward,
            double neighborMaxQPrime, QLearningRouter router, DTNHost pendingHost) {

        initDestinationIfNeeded(destination);
        double[][] table = qvalues.get(destination);

        // Logical check for Goal State (Equation 9/10 logic)
        // If reward is 1, it means the message is delivered. No more future hops.
        double futureComponent = (reward >= 1.0) ? 0.0 : (discountFactor * neighborMaxQPrime);

        // Core Q-Learning Formula (Equation 5)
        double currentQ = table[previousState][action];
        double updatedQ = (1.0 - learningRate) * currentQ + (learningRate * (reward + futureComponent));

        // Save the updated knowledge
        table[previousState][action] = updatedQ;

        // Synchronize waitForReward map to track learning progress
        Map<Integer, Tuple<DTNHost, List<Integer>>> waitForReward = router.getMapWaitForReward();
        waitForReward.put(previousState, new Tuple<>(pendingHost, new ArrayList<Integer>()));
    }

    /**
     * Periodically reduces Q-values to handle node mobility and stale data.
     */
    public void ageQTable() {
        if (qvalues.isEmpty())
            return;

        for (double[][] table : qvalues.values()) {
            for (int i = 0; i < states; i++) {
                for (int j = 0; j < actions; j++) {
                    table[i][j] *= Q_AGEING_FACTOR;
                }
            }
        }
    }

    /**
     * Selects the best relay node based on the Q-Table and Exploration Policy.
     */
    public int GetAction(int destination, int state, Map<Integer, Tuple<DTNHost, List<Integer>>> waitForReward,
            boolean isWaitingReward) {
        initDestinationIfNeeded(destination);
        return explorationPolicy.ChooseAction(qvalues.get(destination)[state], waitForReward, isWaitingReward);
    }

    /**
     * Helper to retrieve specific Q-Value for Fusion Score calculation.
     */
    public double getQV(int destination, int state, int action) {
        initDestinationIfNeeded(destination);
        // Safety check for index out of bounds
        if (state >= states || action >= actions)
            return 0.0;
        return qvalues.get(destination)[state][action];
    }
}