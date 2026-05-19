import matplotlib.pyplot as plt
import os


def parse_time_report(filepath):
    sim_times, delivery, overhead, latency, dropped = [], [], [], [], []

    if not os.path.exists(filepath):
        print(f"⚠️ File tidak ditemukan: {filepath}")
        return sim_times, delivery, overhead, latency, dropped

    print(f"Membaca data dari: {filepath}")
    with open(filepath, "r") as f:
        # Melewati baris pertama jika itu adalah header teks
        first_line = True
        for line in f:
            if first_line:
                first_line = False
                # Jika baris pertama mengandung huruf (header), lewati
                if "time" in line or "delivery_prob" in line:
                    continue

            parts = line.strip().split()
            if len(parts) >= 5:
                try:
                    sim_times.append(float(parts[0]))
                    delivery.append(float(parts[1]))
                    overhead.append(float(parts[2]))
                    latency.append(float(parts[3]))
                    dropped.append(float(parts[4]))
                except ValueError:
                    continue
    return sim_times, delivery, overhead, latency, dropped


def plot_time_series(report_files_per_protocol):
    fig, axs = plt.subplots(2, 2, figsize=(14, 10))
    fig.suptitle(
        "Evaluasi Kinerja Berdasarkan Waktu Simulasi\n(Menggunakan Data MessageStatsTimeReport)",
        fontsize=16,
        fontweight="bold",
    )

    colors = ["#1f77b4", "#ff7f0e", "#2ca02c"]
    markers = ["o", "s", "^"]

    protocols = list(report_files_per_protocol.keys())

    for i, proto in enumerate(protocols):
        filepath = report_files_per_protocol[proto]
        sim_times, delivery, overhead, latency, dropped = parse_time_report(filepath)

        if not sim_times:
            continue

        axs[0, 0].plot(
            sim_times,
            delivery,
            marker=markers[i],
            color=colors[i],
            label=proto,
            linewidth=2,
            markersize=6,
        )
        axs[0, 1].plot(
            sim_times,
            overhead,
            marker=markers[i],
            color=colors[i],
            label=proto,
            linewidth=2,
            markersize=6,
        )
        axs[1, 0].plot(
            sim_times,
            latency,
            marker=markers[i],
            color=colors[i],
            label=proto,
            linewidth=2,
            markersize=6,
        )
        axs[1, 1].plot(
            sim_times,
            dropped,
            marker=markers[i],
            color=colors[i],
            label=proto,
            linewidth=2,
            markersize=6,
        )

    # 1. Delivery Ratio
    axs[0, 0].set_title("Delivery Ratio (delivery_prob)")
    axs[0, 0].set_xlabel("Simulation Time")
    axs[0, 0].set_ylabel("Delivery Probability")
    axs[0, 0].grid(True, linestyle="--", alpha=0.7)
    axs[0, 0].legend()

    # 2. Overhead Ratio
    axs[0, 1].set_title("Overhead Ratio")
    axs[0, 1].set_xlabel("Simulation Time")
    axs[0, 1].set_ylabel("Ratio")
    axs[0, 1].grid(True, linestyle="--", alpha=0.7)
    axs[0, 1].legend()

    # 3. Average Latency
    axs[1, 0].set_title("Average Latency (latency_avg)")
    axs[1, 0].set_xlabel("Simulation Time")
    axs[1, 0].set_ylabel("Time (seconds)")
    axs[1, 0].grid(True, linestyle="--", alpha=0.7)
    axs[1, 0].legend()

    # 4. Drop Messages
    axs[1, 1].set_title("Dropped Messages")
    axs[1, 1].set_xlabel("Simulation Time")
    axs[1, 1].set_ylabel("Number of Messages")
    axs[1, 1].grid(True, linestyle="--", alpha=0.7)
    axs[1, 1].legend()

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    output_filename = "SkripsiTest/RunningPase1/Haggle/charts/routing_comparison_Haggle_chart.png"
    plt.savefig(output_filename, dpi=300)
    print(
        f"\n✅ Grafik linier berhasil di-generate dan disimpan sebagai '{os.path.abspath(output_filename)}'"
    )


if __name__ == "__main__":
    print(
        "=== Skrip Plotting Kinerja The ONE Simulator (Parser MessageStatsTimeReport) ==="
    )

    # Isi array di bawah ini dengan nama file report hasil generate MessageStatsTimeReport
    report_files = {
        "ORQLCI (State-Aware)": "SkripsiTest/RunningPase1/Haggle/ORQLCI/Result/ORQLCI_Haggle_Infocom5_Final_MessageStatsTimeReport.txt",
        "Epidemic": "SkripsiTest/RunningPase1/Haggle/Epidemic/Result/Epidemic_Haggle_Infocom5_Baseline_MessageStatsReport.txt",
        "Prophet": "SkripsiTest/RunningPase1/Haggle/Prophet/Result/Prophet_Haggle_Infocom5_Baseline_MessageStatsReport.txt",
    }

    plot_time_series(report_files)
