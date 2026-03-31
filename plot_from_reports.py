import matplotlib.pyplot as plt
import numpy as np
import os

def parse_message_stats(filepath):
    """
    Fungsi untuk membaca file laporan MessageStatsReport dari The ONE simulator
    dan mengambil nilai matriks yang dibutuhkan.
    """
    metrics = {
        'delivery_prob': 0.0,
        'overhead_ratio': 0.0,
        'latency_avg': 0.0,
        'dropped': 0
    }
    
    if not os.path.exists(filepath):
        print(f"⚠️ Peringatan: File '{filepath}' tidak ditemukan! Menggunakan nilai 0.")
        return metrics

    print(f"Membaca data dari: {filepath}")
    with open(filepath, 'r') as file:
        for line in file:
            line = line.strip()
            if ": " in line:
                key, val = line.split(": ", 1)
                
                # Ekstrak data jika kunci cocok
                if key == 'delivery_prob':
                    metrics['delivery_prob'] = float(val)
                elif key == 'overhead_ratio':
                    metrics['overhead_ratio'] = float(val)
                elif key == 'latency_avg':
                    metrics['latency_avg'] = float(val)
                elif key == 'dropped':
                    metrics['dropped'] = float(val)
                    
    return metrics

def plot_comparison(protocols, delivery_data, overhead_data, latency_data, drop_data):
    # Konfigurasi plot
    fig, axs = plt.subplots(2, 2, figsize=(14, 10))
    fig.suptitle('Perbandingan Evaluasi Kinerja (Baca dari Report)', fontsize=16, fontweight='bold')

    colors = ['#1f77b4', '#ff7f0e', '#2ca02c']

    # 1. Delivery Ratio
    axs[0, 0].bar(protocols, delivery_data, color=colors, edgecolor='black')
    axs[0, 0].set_title('Delivery Ratio (delivery_prob)')
    axs[0, 0].grid(axis='y', linestyle='--', alpha=0.7)
    for i, v in enumerate(delivery_data):
        axs[0, 0].text(i, v + (max(delivery_data)*0.01 if max(delivery_data)>0 else 0), f"{v:.4f}", ha='center', va='bottom')

    # 2. Overhead Ratio
    axs[0, 1].bar(protocols, overhead_data, color=colors, edgecolor='black')
    axs[0, 1].set_title('Overhead Ratio')
    axs[0, 1].grid(axis='y', linestyle='--', alpha=0.7)
    for i, v in enumerate(overhead_data):
        axs[0, 1].text(i, v + (max(overhead_data)*0.01 if max(overhead_data)>0 else 0), f"{v:.2f}", ha='center', va='bottom')

    # 3. Average Latency
    axs[1, 0].bar(protocols, latency_data, color=colors, edgecolor='black')
    axs[1, 0].set_title('Average Latency (latency_avg)')
    axs[1, 0].grid(axis='y', linestyle='--', alpha=0.7)
    for i, v in enumerate(latency_data):
        axs[1, 0].text(i, v + (max(latency_data)*0.01 if max(latency_data)>0 else 0), f"{v:.1f}", ha='center', va='bottom')

    # 4. Drop Messages
    axs[1, 1].bar(protocols, drop_data, color=colors, edgecolor='black')
    axs[1, 1].set_title('Dropped Messages')
    axs[1, 1].grid(axis='y', linestyle='--', alpha=0.7)
    for i, v in enumerate(drop_data):
        axs[1, 1].text(i, v + (max(drop_data)*0.01 if max(drop_data)>0 else 0), f"{int(v)}", ha='center', va='bottom')

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    
    # Simpan hasil plot
    output_filename = 'routing_comparison_from_report.png'
    plt.savefig(output_filename, dpi=300)
    print(f"\n✅ Grafik berhasil di-generate dan disimpan sebagai '{os.path.abspath(output_filename)}'")

if __name__ == "__main__":
    print("=== Skrip Plotting Otomatis dari MessageStatsReport ===")
    
    # DAFTAR PROTOKOL DAN FILE REPORT YANG BERSESUAIAN
    # Silakan sesuaikan jalur (path) dan nama file laporan jika berbeda.
    # Disini sebagai contoh kita asumsikan StandardQL adalah Pure RL.
    
    report_files = {
        'ORQLCI (CCRouting)': 'reports/Bench-ORQLCI-Table1_MessageStatsReport.txt',
        'Pure RL (StandardQL)': 'reports/Bench-StandardQL-Table1_MessageStatsReport.txt',
        'Prophet': 'reports/Bench-Prophet-Table1_MessageStatsReport.txt'
    }

    protocols = list(report_files.keys())
    
    delivery_data = []
    overhead_data = []
    latency_data = []
    drop_data = []

    # Membaca data dari tiap file report
    for proto in protocols:
        filepath = report_files[proto]
        stats = parse_message_stats(filepath)
        
        delivery_data.append(stats['delivery_prob'])
        overhead_data.append(stats['overhead_ratio'])
        latency_data.append(stats['latency_avg'])
        drop_data.append(stats['dropped'])

    # Membuat Grafik
    plot_comparison(protocols, delivery_data, overhead_data, latency_data, drop_data)
