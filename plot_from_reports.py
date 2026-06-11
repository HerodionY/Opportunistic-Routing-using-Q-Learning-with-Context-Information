import matplotlib.pyplot as plt
import numpy as np
import os


def parse_message_stats(filepath):
    """
    Fungsi untuk membaca file laporan MessageStatsReport dari The ONE simulator
    dan mengambil nilai matriks yang dibutuhkan.
    """
    metrics = {
        "delivery_prob": 0.0,
        "overhead_ratio": 0.0,
        "latency_avg": 0.0,
    }

    if not os.path.exists(filepath):
        print(f"⚠️ Peringatan: File '{filepath}' tidak ditemukan! Menggunakan nilai 0.")
        return metrics

    print(f"Membaca data dari: {filepath}")
    with open(filepath, "r") as file:
        for line in file:
            line = line.strip()
            if ": " in line:
                key, val = line.split(": ", 1)

                if key == "delivery_prob":
                    metrics["delivery_prob"] = float(val)
                elif key == "overhead_ratio":
                    metrics["overhead_ratio"] = float(val)
                elif key == "latency_avg":
                    metrics["latency_avg"] = float(val)

    return metrics


# Warna konsisten dengan plot_node_death.py
COLORS = {
    "ORQLCI": "#2ecc71",            # Hijau
    "ORQLCI_Without_EF": "#f39c12", # Oranye
    "Epidemic": "#e74c3c",          # Merah
    "Prophet": "#3498db",           # Biru
}


def plot_comparison(protocols, delivery_data, overhead_data, latency_data):
    # 3 metric → 1 baris × 3 kolom
    fig, axs = plt.subplots(1, 3, figsize=(16, 5.5))
    fig.suptitle(
        "Perbandingan Evaluasi Kinerja",
        fontsize=16,
        fontweight="bold",
    )

    bar_colors = [COLORS.get(p, "#7f8c8d") for p in protocols]

    # 1. Delivery Ratio
    axs[0].bar(protocols, delivery_data, color=bar_colors, edgecolor="black")
    axs[0].set_title("Delivery Ratio (delivery_prob)")
    axs[0].grid(axis="y", linestyle="--", alpha=0.7)
    for i, v in enumerate(delivery_data):
        axs[0].text(
            i,
            v + (max(delivery_data) * 0.01 if max(delivery_data) > 0 else 0),
            f"{v:.4f}",
            ha="center",
            va="bottom",
        )

    # 2. Overhead Ratio
    axs[1].bar(protocols, overhead_data, color=bar_colors, edgecolor="black")
    axs[1].set_title("Overhead Ratio")
    axs[1].grid(axis="y", linestyle="--", alpha=0.7)
    for i, v in enumerate(overhead_data):
        axs[1].text(
            i,
            v + (max(overhead_data) * 0.01 if max(overhead_data) > 0 else 0),
            f"{v:.2f}",
            ha="center",
            va="bottom",
        )

    # 3. Average Latency
    axs[2].bar(protocols, latency_data, color=bar_colors, edgecolor="black")
    axs[2].set_title("Average Latency (latency_avg)")
    axs[2].grid(axis="y", linestyle="--", alpha=0.7)
    for i, v in enumerate(latency_data):
        axs[2].text(
            i,
            v + (max(latency_data) * 0.01 if max(latency_data) > 0 else 0),
            f"{v:.1f}",
            ha="center",
            va="bottom",
        )

    # Rotasi xtick label karena ORQLCI_Without_EF agak panjang
    for ax in axs:
        ax.tick_params(axis="x", rotation=20)
        for lbl in ax.get_xticklabels():
            lbl.set_ha("right")

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])

    output_filename = "baru/haggle/Stat_Chart_haggle.png"
    output_directory = os.path.dirname(output_filename)
    if not os.path.exists(output_directory):
        os.makedirs(output_directory)

    plt.savefig(output_filename, dpi=300)
    print(
        f"\n[SUKSES] Grafik berhasil di-generate dan disimpan sebagai '{os.path.abspath(output_filename)}'"
    )


if __name__ == "__main__":
    print("=== Skrip Plotting Otomatis dari MessageStatsReport ===")

    report_files = {
        "ORQLCI": "baru/haggle/ORQLCI_Haggle_Infocom5_Final_MessageStatsReport.txt",
        "ORQLCI_Without_EF": "baru/haggle/ORQLCI_Haggle_Infocom5_Final-without_energy_MessageStatsReport.txt",
        "Epidemic": "baru/haggle/Epidemic_Haggle_Infocom5_Baseline_MessageStatsReport.txt",
        "Prophet": "baru/haggle/Prophet_Haggle_Infocom5_Baseline_MessageStatsReport.txt",
    }

    protocols = list(report_files.keys())

    delivery_data = []
    overhead_data = []
    latency_data = []

    for proto in protocols:
        filepath = report_files[proto]
        stats = parse_message_stats(filepath)

        delivery_data.append(stats["delivery_prob"])
        overhead_data.append(stats["overhead_ratio"])
        latency_data.append(stats["latency_avg"])

    plot_comparison(protocols, delivery_data, overhead_data, latency_data)