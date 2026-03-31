import matplotlib.pyplot as plt
import numpy as np
import os

def parse_message_stats(filepath):
    metrics = {
        'sim_time': 0.0,
        'delivery_prob': 0.0,
        'overhead_ratio': 0.0,
        'latency_avg': 0.0,
        'dropped': 0
    }
    
    if not os.path.exists(filepath):
        print(f"⚠️ Peringatan: File '{filepath}' tidak ditemukan! Melewatkan file ini.")
        return None

    print(f"Membaca data dari: {filepath}")
    with open(filepath, 'r') as file:
        for line in file:
            line = line.strip()
            if ": " in line:
                key, val = line.split(": ", 1)
                
                if key == 'sim_time':
                    metrics['sim_time'] = float(val)
                elif key == 'delivery_prob':
                    metrics['delivery_prob'] = float(val)
                elif key == 'overhead_ratio':
                    metrics['overhead_ratio'] = float(val)
                elif key == 'latency_avg':
                    metrics['latency_avg'] = float(val)
                elif key == 'dropped':
                    metrics['dropped'] = float(val)
                    
    return metrics

def plot_time_series(protocol_data):
    fig, axs = plt.subplots(2, 2, figsize=(14, 10))
    fig.suptitle('Evaluasi Kinerja Routing Protocol Berdasarkan Waktu Simulasi\n(Berdasarkan Report The ONE)', fontsize=16, fontweight='bold')

    colors = ['#1f77b4', '#ff7f0e', '#2ca02c']
    markers = ['o', 's', '^']

    protocols = list(protocol_data.keys())

    for i, proto in enumerate(protocols):
        # Ambil data untuk protokol ini
        data_points = protocol_data[proto]
        
        if not data_points:
            continue
            
        # Urutkan berdasarkan sim_time
        data_points.sort(key=lambda x: x['sim_time'])
        
        sim_times = [dp['sim_time'] for dp in data_points]
        delivery = [dp['delivery_prob'] for dp in data_points]
        overhead = [dp['overhead_ratio'] for dp in data_points]
        latency = [dp['latency_avg'] for dp in data_points]
        dropped = [dp['dropped'] for dp in data_points]

        # Plot keempat metrik
        axs[0, 0].plot(sim_times, delivery, marker=markers[i], color=colors[i], label=proto, linewidth=2, markersize=8)
        axs[0, 1].plot(sim_times, overhead, marker=markers[i], color=colors[i], label=proto, linewidth=2, markersize=8)
        axs[1, 0].plot(sim_times, latency,  marker=markers[i], color=colors[i], label=proto, linewidth=2, markersize=8)
        axs[1, 1].plot(sim_times, dropped,  marker=markers[i], color=colors[i], label=proto, linewidth=2, markersize=8)

    # 1. Delivery Ratio
    axs[0, 0].set_title('Delivery Ratio (delivery_prob)')
    axs[0, 0].set_xlabel('Simulation Time (detik)')
    axs[0, 0].set_ylabel('Delivery Probability')
    axs[0, 0].grid(True, linestyle='--', alpha=0.7)
    axs[0, 0].legend()

    # 2. Overhead Ratio
    axs[0, 1].set_title('Overhead Ratio')
    axs[0, 1].set_xlabel('Simulation Time (detik)')
    axs[0, 1].set_ylabel('Ratio')
    axs[0, 1].grid(True, linestyle='--', alpha=0.7)
    axs[0, 1].legend()

    # 3. Average Latency
    axs[1, 0].set_title('Average Latency (latency_avg)')
    axs[1, 0].set_xlabel('Simulation Time (detik)')
    axs[1, 0].set_ylabel('Time (seconds)')
    axs[1, 0].grid(True, linestyle='--', alpha=0.7)
    axs[1, 0].legend()

    # 4. Drop Messages
    axs[1, 1].set_title('Dropped Messages')
    axs[1, 1].set_xlabel('Simulation Time (detik)')
    axs[1, 1].set_ylabel('Number of Messages')
    axs[1, 1].grid(True, linestyle='--', alpha=0.7)
    axs[1, 1].legend()

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    output_filename = 'routing_comparison_time_from_report.png'
    plt.savefig(output_filename, dpi=300)
    print(f"\n✅ Grafik linier berhasil di-generate dan disimpan sebagai '{os.path.abspath(output_filename)}'")


if __name__ == "__main__":
    print("=== Skrip Plotting Kinerja The ONE Simulator (Time Series Berdasarkan Report) ===")
    
    # =========================================================================
    # DAFTAR PROTOKOL DAN FILE REPORT UNTUK TIAP WAKTU SIMULASI
    # Untuk mendapatkan grafik time series (berdasarkan waktu berjalan), 
    # Anda harus memberikan beberapa file report per protokol.
    # Setiap report mewakili simulasi yang di-stop di waktu yang berbeda
    # (misalnya simulasi untuk sim_time=1000, 2000, 3000, dst).
    # =========================================================================
    
    # Silakan ubah file list di bawah ini dengan file report Anda
    report_files_per_protocol = {
        'ORQLCI (CCRouting)': [
            # Contoh (Anda butuh lebih dari 1 file agar bisa menjadi garis)
            'reports/Bench-ORQLCI-Table1_MessageStatsReport.txt', 
            'reports/Bench-ORQLCI-Table12_MessageStatsReport.txt' # hapus/ganti ini
        ],
        'Pure RL (StandardQL)': [
            # Contoh
            'reports/Bench-StandardQL-Table1_MessageStatsReport.txt',
            'reports/Bench-StandardQL_MessageStatsReport.txt'
        ],
        'Prophet': [
            # Contoh
            'reports/Bench-Prophet-Table1_MessageStatsReport.txt',
            'reports/Bench-Prophet_MessageStatsReport.txt'
        ]
    }

    protocol_data = {}

    for proto, files in report_files_per_protocol.items():
        data_points = []
        for filepath in files:
            stats = parse_message_stats(filepath)
            if stats is not None:
                data_points.append(stats)
        
        protocol_data[proto] = data_points

    # Panggil fungsi plot
    plot_time_series(protocol_data)
