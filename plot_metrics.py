import matplotlib.pyplot as plt
import numpy as np
import os

def plot_comparison(delivery_data, overhead_data, latency_data, drop_data):
    # Nama-nama protokol yang dibandingkan
    protocols = ['ORQLCI (CCRouting)', 'Pure RL', 'Prophet']

    # Konfigurasi plot
    fig, axs = plt.subplots(2, 2, figsize=(14, 10))
    fig.suptitle('Perbandingan Evaluasi Kinerja Routing Protocol', fontsize=16, fontweight='bold')

    # Warna untuk masing-masing bar
    colors = ['#1f77b4', '#ff7f0e', '#2ca02c']

    # 1. Delivery Ratio
    axs[0, 0].bar(protocols, delivery_data, color=colors, edgecolor='black')
    axs[0, 0].set_title('Delivery Ratio')
    axs[0, 0].set_ylabel('Delivery Probability')
    axs[0, 0].grid(axis='y', linestyle='--', alpha=0.7)
    # Menambahkan nilai di atas bar
    for i, v in enumerate(delivery_data):
        axs[0, 0].text(i, v + 0.01, str(v), ha='center', va='bottom')

    # 2. Overhead Ratio
    axs[0, 1].bar(protocols, overhead_data, color=colors, edgecolor='black')
    axs[0, 1].set_title('Overhead Ratio')
    axs[0, 1].set_ylabel('Ratio')
    axs[0, 1].grid(axis='y', linestyle='--', alpha=0.7)
    for i, v in enumerate(overhead_data):
        axs[0, 1].text(i, v + 0.5, str(v), ha='center', va='bottom')

    # 3. Average Latency
    axs[1, 0].bar(protocols, latency_data, color=colors, edgecolor='black')
    axs[1, 0].set_title('Average Latency')
    axs[1, 0].set_ylabel('Time (seconds)')
    axs[1, 0].grid(axis='y', linestyle='--', alpha=0.7)
    for i, v in enumerate(latency_data):
        axs[1, 0].text(i, v + 2, str(v), ha='center', va='bottom')

    # 4. Drop Messages
    axs[1, 1].bar(protocols, drop_data, color=colors, edgecolor='black')
    axs[1, 1].set_title('Dropped Messages')
    axs[1, 1].set_ylabel('Number of Messages')
    axs[1, 1].grid(axis='y', linestyle='--', alpha=0.7)
    for i, v in enumerate(drop_data):
        axs[1, 1].text(i, v + 10, str(v), ha='center', va='bottom')

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    
    # Simpan hasil plot ke gambar
    output_filename = 'routing_comparison_chart.png'
    plt.savefig(output_filename, dpi=300)
    print(f"✅ Grafik berhasil disimpan sebagai '{os.path.abspath(output_filename)}'")
    
    # Tampilkan plot langsung (opsional, butuh GUI)
    plt.show()

if __name__ == "__main__":
    print("=== Skrip Plotting Kinerja The ONE Simulator ===")
    print("Silakan masukkan data evaluasi (MessageStatsReport) yang telah Anda dapatkan.")
    print("Data diurutkan untuk: [1] ORQLCI (CCRouting), [2] Pure RL, [3] Prophet\n")

    # [DATA DUMMY SEBAGAI CONTOH, SILAKAN UBAH DI BAWAH INI] #
    # Urutan data list: [Nilai ORQLCI, Nilai PureRL, Nilai Prophet]
    
    # 1. Delivery ratio (bisa diambil dari delivery_prob di MessageStatsReport)
    # Contoh data dummy:
    delivery_ratio_data = [0.85, 0.65, 0.55] 

    # 2. Overhead ratio (overhead_ratio di laporan)
    overhead_ratio_data = [15.4, 25.2, 45.1] 

    # 3. Average latency (latency_avg di laporan)
    avg_latency_data = [120.5, 180.3, 220.0] 

    # 4. Drop messages (dropped di laporan)
    drop_messages_data = [200, 350, 500] 

    print("Data yang digunakan:")
    print(f"Protocols        : ORQLCI, Pure RL, Prophet")
    print(f"Delivery Ratio   : {delivery_ratio_data}")
    print(f"Overhead Ratio   : {overhead_ratio_data}")
    print(f"Average Latency  : {avg_latency_data}")
    print(f"Dropped Messages : {drop_messages_data}\n")

    print("- Mengenerate grafik...")
    plot_comparison(delivery_ratio_data, overhead_ratio_data, avg_latency_data, drop_messages_data)
