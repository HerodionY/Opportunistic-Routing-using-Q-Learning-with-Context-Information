import matplotlib.pyplot as plt
import numpy as np
import os

def plot_time_series(sim_times, delivery_data, overhead_data, latency_data, drop_data):
    # Nama-nama protokol yang dibandingkan
    protocols = ['ORQLCI (CCRouting)', 'Pure RL', 'Prophet']

    # Konfigurasi plot
    fig, axs = plt.subplots(2, 2, figsize=(14, 10))
    fig.suptitle('Evaluasi Kinerja Routing Protocol Berdasarkan Simulation Time', fontsize=16, fontweight='bold')

    # Warna dan marker untuk masing-masing baris (garis)
    colors = ['#1f77b4', '#ff7f0e', '#2ca02c']
    markers = ['o', 's', '^']

    # 1. Delivery Ratio
    for i, proto in enumerate(protocols):
        axs[0, 0].plot(sim_times, delivery_data[i], marker=markers[i], color=colors[i], label=proto, linewidth=2)
    axs[0, 0].set_title('Delivery Ratio')
    axs[0, 0].set_xlabel('Simulation Time')
    axs[0, 0].set_ylabel('Delivery Probability')
    axs[0, 0].grid(True, linestyle='--', alpha=0.7)
    axs[0, 0].legend()

    # 2. Overhead Ratio
    for i, proto in enumerate(protocols):
        axs[0, 1].plot(sim_times, overhead_data[i], marker=markers[i], color=colors[i], label=proto, linewidth=2)
    axs[0, 1].set_title('Overhead Ratio')
    axs[0, 1].set_xlabel('Simulation Time')
    axs[0, 1].set_ylabel('Ratio')
    axs[0, 1].grid(True, linestyle='--', alpha=0.7)
    axs[0, 1].legend()

    # 3. Average Latency
    for i, proto in enumerate(protocols):
        axs[1, 0].plot(sim_times, latency_data[i], marker=markers[i], color=colors[i], label=proto, linewidth=2)
    axs[1, 0].set_title('Average Latency')
    axs[1, 0].set_xlabel('Simulation Time')
    axs[1, 0].set_ylabel('Time (seconds)')
    axs[1, 0].grid(True, linestyle='--', alpha=0.7)
    axs[1, 0].legend()

    # 4. Drop Messages
    for i, proto in enumerate(protocols):
        axs[1, 1].plot(sim_times, drop_data[i], marker=markers[i], color=colors[i], label=proto, linewidth=2)
    axs[1, 1].set_title('Dropped Messages')
    axs[1, 1].set_xlabel('Simulation Time')
    axs[1, 1].set_ylabel('Number of Messages')
    axs[1, 1].grid(True, linestyle='--', alpha=0.7)
    axs[1, 1].legend()

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    
    # Simpan hasil plot ke gambar
    output_filename = 'routing_comparison_time_chart.png'
    plt.savefig(output_filename, dpi=300)
    print(f"✅ Grafik linier berhasil disimpan sebagai '{os.path.abspath(output_filename)}'")

if __name__ == "__main__":
    print("=== Skrip Plotting Kinerja The ONE Simulator (Time Series) ===")
    print("Silakan masukkan data evaluasi Anda yang dicatat pada waktu simulasi yang berbeda.")
    
    # [DATA DUMMY SEBAGAI CONTOH, SILAKAN UBAH DI BAWAH INI] #
    
    # Waktu simulasi (misal dalam jam atau detik)
    sim_times = [1000, 2000, 3000, 4000, 5000]

    # Data berupa list of lists: [Data ORQLCI, Data PureRL, Data Prophet]
    
    # 1. Delivery ratio
    delivery_ratio_data = [
        [0.55, 0.70, 0.80, 0.85, 0.88], # ORQLCI
        [0.45, 0.55, 0.60, 0.65, 0.68], # Pure RL
        [0.35, 0.45, 0.50, 0.52, 0.55]  # Prophet
    ]

    # 2. Overhead ratio
    overhead_ratio_data = [
        [10.5, 12.0, 14.2, 15.4, 16.0], # ORQLCI
        [15.0, 18.5, 22.0, 25.2, 28.0], # Pure RL
        [25.0, 32.0, 40.5, 45.1, 50.3]  # Prophet
    ]

    # 3. Average latency
    avg_latency_data = [
        [150.0, 140.5, 130.0, 120.5, 115.0], # ORQLCI
        [200.0, 190.0, 185.0, 180.3, 178.0], # Pure RL
        [250.0, 240.5, 230.0, 220.0, 215.5]  # Prophet
    ]

    # 4. Drop messages
    drop_messages_data = [
        [50, 100, 150, 200, 220],    # ORQLCI
        [80, 160, 250, 350, 420],    # Pure RL
        [120, 240, 380, 500, 650]    # Prophet
    ]

    print("- Mengenerate grafik garis (linier) berdasarkan simulation time...")
    plot_time_series(sim_times, delivery_ratio_data, overhead_ratio_data, avg_latency_data, drop_messages_data)
