package reinforcement;

import core.DTNHost;
import core.Tuple;
import java.util.*;
import routing.QLearningRouter;

/**
 * Implementation of Q-Learning for ORQLCI.
 * Perbaikan: Mendukung Multiple States (Context-Aware),
 * Optimasi Max Search, dan Ageing yang Konsisten.
 * * @author Chornael Damar Kesuma (Final Revised Version)
 */
public class QLearning {

    private int states;
    private int actions;

    // Q-Table: Map<DestinationAddress, double[State][Action]>
    private Map<Integer, double[][]> qvalues;

    private IExplorationPolicy explorationPolicy;

    // Parameters for ORQLCI Logic
    private double discountFactor = 0.0; // Persamaan (7): gamma_d
    private double learningRate = 0.0; // Persamaan (5): alpha

    // Ageing Factor: Menyusutkan nilai Q yang sudah lama tidak di-update
    private static final double Q_AGEING_FACTOR = 0.98;

    public QLearning(int states, int actions, IExplorationPolicy explorationPolicy, boolean randomize) {
        this.states = states;
        this.actions = actions;
        this.explorationPolicy = explorationPolicy;
        this.qvalues = new HashMap<>();
    }

    /**
     * Inisialisasi Q-Table untuk destinasi baru saat pesan pertama kali muncul.
     */
    private void initDestinationIfNeeded(int destination) {
        if (!qvalues.containsKey(destination)) {
            // Matriks ukuran [states][actions]
            // states: 0 (Lega), 1 (Sedang), 2 (Kritis)
            // actions: ID Node tetangga (0 - totalAction)
            double[][] newQTable = new double[states][actions];

            // Inisialisasi dengan nilai random kecil untuk mendorong eksplorasi awal
            Random r = new Random();
            for (int i = 0; i < states; i++) {
                for (int j = 0; j < actions; j++) {
                    newQTable[i][j] = r.nextDouble() / 100.0;
                }
            }
            qvalues.put(destination, newQTable);
        }
    }

    /**
     * Persamaan (5) Part: Alpha dinamis berdasarkan frekuensi kunjungan.
     */
    public void setLearningRate(double visitCount, double coeff) {
        if (visitCount <= 0)
            visitCount = 1;
        this.learningRate = Math.min(1.0, coeff / visitCount);
    }

    /**
     * Persamaan (7): gamma_d = gamma * BF_x
     * Mengatur bobot future reward berdasarkan kesehatan buffer tetangga.
     */
    public void setDiscountFactorDynamic(double baseGamma, double bufferFactor) {
        this.discountFactor = Math.max(0.0, Math.min(1.0, baseGamma * bufferFactor));
    }

    /**
     * Persamaan (10): max Q' = max(Q_d(x,y)) * P(x,y)
     * Digunakan untuk mengambil "janji" keberhasilan dari node tetangga.
     */
    public double getNeighborMaxQPrime(int destination, double pEncounter) {
        initDestinationIfNeeded(destination);
        double[][] table = qvalues.get(destination);
        double maxQValue = 0.0;

        // Mencari nilai Q tertinggi di semua state dan action milik tetangga
        // Ini merepresentasikan "potensi terbaik" yang dimiliki tetangga tersebut
        for (int s = 0; s < states; s++) {
            for (int a = 0; a < actions; a++) {
                if (table[s][a] > maxQValue) {
                    maxQValue = table[s][a];
                }
            }
        }

        // Filter dengan Encounter Probability (PRoPHET)
        return maxQValue * pEncounter;
    }

    /**
     * Persamaan (5) & (9): Q-Value Update Rule.
     * Q_new = (1-alpha)*Q_old + alpha * [Reward + gamma_d * max_Q_prime]
     */
    public void UpdateState(int destination, int state, int action, double reward,
            double neighborMaxQPrime, QLearningRouter router, DTNHost pendingHost) {

        initDestinationIfNeeded(destination);
        double[][] table = qvalues.get(destination);

        // Logika Goal State (Persamaan 9):
        // Jika sampai ke tujuan (reward 1.0), tidak ada langkah masa depan
        // (futureComponent = 0)
        double futureComponent = (reward >= 1.0) ? 0.0 : (discountFactor * neighborMaxQPrime);

        // Eksekusi Rumus Bellman yang dimodifikasi
        double currentQ = table[state][action];
        double updatedQ = (1.0 - learningRate) * currentQ + (learningRate * (reward + futureComponent));

        // Update Tabel
        table[state][action] = updatedQ;
    }

    /**
     * Mekanisme Ageing untuk menangani mobilitas node.
     * Tanpa ini, agen akan terus percaya pada rute yang sudah tidak ada (basi).
     */
    public void ageQTable() {
        if (qvalues.isEmpty())
            return;

        for (double[][] table : qvalues.values()) {
            for (int s = 0; s < states; s++) {
                for (int a = 0; a < actions; a++) {
                    table[s][a] *= Q_AGEING_FACTOR;
                }
            }
        }
    }

    /**
     * Mengambil keputusan aksi berdasarkan Policy (Epsilon-Greedy).
     */
    public int GetAction(int destination, int state, Map<Integer, List<Integer>> waitForReward,
            boolean isWaitingReward) {
        initDestinationIfNeeded(destination);

        // Memanggil policy untuk memilih action terbaik atau explore
        // Kita kirimkan baris tabel yang sesuai dengan state saat ini (Context-Aware)
        return explorationPolicy.ChooseAction(qvalues.get(destination)[state], null, isWaitingReward);
    }

    /**
     * Utility untuk mengambil nilai Q spesifik (digunakan di Fusion Score).
     */
    public double getQV(int destination, int state, int action) {
        initDestinationIfNeeded(destination);
        if (state >= states || action >= actions || state < 0 || action < 0) {
            return 0.0;
        }
        return qvalues.get(destination)[state][action];
    }

    // Getter untuk keperluan Debugging atau Monitoring
    public Map<Integer, double[][]> getQValues() {
        return this.qvalues;
    }
}