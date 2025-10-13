# Opportunistic Routing using Q-Learning & Context Information (ORQLCI)

[cite_start]This repository contains the official implementation of the **ORQLCI** algorithm, an intelligent routing scheme for **Opportunistic Networks**, as described in our research paper[cite: 1].

[cite_start]This algorithm leverages **Q-Learning**, a Reinforcement Learning technique, to make more efficient message forwarding decisions in highly dynamic and frequently disconnected network environments[cite: 19].

## 🎯 Main Goal

The primary objective of ORQLCI is to enhance overall network performance by:
* [cite_start]**Improving Message Delivery Ratio:** Ensuring more messages successfully reach their destination[cite: 14].
* [cite_start]**Reducing Routing Overhead:** Minimizing unnecessary message transmissions to conserve resources[cite: 14].
* [cite_start]**Decreasing Network Latency:** Reducing the delay in message delivery from source to destination[cite: 14].

## 🧠 How It Works

[cite_start]Each node in the network acts as an independent learning agent[cite: 11]. [cite_start]The agent makes forwarding decisions based on **contextual information** from other nodes it encounters, primarily[cite: 13, 37]:

1.  [cite_start]**Encounter Probability:** The likelihood that a node will meet another node in the future[cite: 59].
2.  [cite_start]**Cache Utilization:** The amount of available buffer space on a node, which indicates its capacity to receive new messages[cite: 12, 79].

[cite_start]This information is integrated into the **Q-Learning** model to dynamically update the routing strategy and select the most optimal relay node[cite: 40, 41].

## 🛠️ Tech Stack

* [cite_start]**Simulation Platform:** [The ONE Simulator](https://akeranen.github.io/the-one/) [cite: 146]
* **Core Algorithm:** Q-Learning
* **Language:** (Specify the language used, e.g., Java/Python)

## 📊 Results

[cite_start]Simulations show that ORQLCI consistently outperforms other algorithms such as **Epidemic**, **Prophet**, and **EC-CW** across various scenarios, particularly in terms of delivery ratio, overhead, and latency[cite: 148, 259].

