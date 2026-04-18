import pandas as pd
import matplotlib.pyplot as plt
import os

# --- KONFIGURASI PATH ---
# Sesuaikan path ini dengan hasil simulasi Anda
REPORT_FILE = "reports/ORQLCI_Final_StateAware/skripsi/ORQLCI_StateAware_Replication_RewardTimeReport.txt"
OUTPUT_CHART = "reports/ORQLCI_Final_StateAware/reward_movement_analysis.png"


def plot_reward_analysis(filepath):
    if not os.path.exists(filepath):
        print(f"File tidak ditemukan: {filepath}")
        print("Pastikan simulasi sudah selesai dijalankan.")
        return

    print(f"Memproses data dari: {filepath}")

    # Membaca data menggunakan pandas
    # Header: time avg_reward avg_td_target avg_q_v s0_reward s1_reward s2_reward total_reward updates
    try:
        df = pd.read_csv(filepath, sep=" ", skipinitialspace=True)
    except Exception as e:
        print(f"Gagal membaca file: {e}")
        return

    # Buat figure multipanel (3 baris, 1 kolom)
    fig, (ax1, ax2, ax3) = plt.subplots(3, 1, figsize=(12, 16), sharex=True)
    fig.suptitle(
        "Analisis Pergerakan Reward & Konvergensi ORQLCI\n(Q-Learning Context-Aware)",
        fontsize=16,
        fontweight="bold",
    )

    # --- PANEL 1: KONVERGENSI (Q-Value & TD Target) ---
    ax1.plot(
        df["time"],
        df["avg_q_v"],
        label="Avg Q-Value (Learned)",
        color="#1f77b4",
        linewidth=2,
    )
    ax1.plot(
        df["time"],
        df["avg_td_target"],
        label="Avg TD-Target (Ideal)",
        color="#ff7f0e",
        linestyle="--",
        alpha=0.7,
    )
    ax1.set_title(
        "1. Grafik Konvergensi Model (Q-Value vs TD-Target)", fontsize=13, loc="left"
    )
    ax1.set_ylabel("Value")
    ax1.grid(True, linestyle=":", alpha=0.6)
    ax1.legend()
    ax1.text(
        0.02,
        0.9,
        "Jika Q-Value mendekati TD-Target,\nberarti model telah konvergen.",
        transform=ax1.transAxes,
        fontsize=9,
        verticalalignment="top",
        bbox=dict(boxstyle="round", facecolor="white", alpha=0.5),
    )

    # --- PANEL 2: PERFORMA REWARD GLOBAL ---
    ax2.plot(
        df["time"],
        df["avg_reward"],
        color="#2ca02c",
        linewidth=2,
        label="Current Reward",
    )
    ax2.fill_between(df["time"], df["avg_reward"], color="#2ca02c", alpha=0.2)
    ax2.set_title("2. Rata-Rata Reward Instan per Interval", fontsize=13, loc="left")
    ax2.set_ylabel("Avg Reward (0.0 - 1.0)")
    ax2.grid(True, linestyle=":", alpha=0.6)
    ax2.set_ylim(0, 1.1)

    # --- PANEL 3: ANALISIS PER-STATE (CONTEXT-AWARE) ---
    ax3.plot(
        df["time"], df["s0_reward"], label="S0: Lega (>70%)", color="#2ca02c", alpha=0.8
    )
    ax3.plot(
        df["time"],
        df["s1_reward"],
        label="S1: Sedang (30-70%)",
        color="#f1c40f",
        alpha=0.8,
    )
    ax3.plot(
        df["time"],
        df["s2_reward"],
        label="S2: Kritis (<30%)",
        color="#e74c3c",
        alpha=0.8,
    )
    ax3.set_title(
        "3. Perbandingan Reward Tiap State (Analisis Context)", fontsize=13, loc="left"
    )
    ax3.set_xlabel("Simulation Time (seconds)")
    ax3.set_ylabel("Reward")
    ax3.grid(True, linestyle=":", alpha=0.6)
    ax3.legend(title="Buffer Status")

    # Tambah keterangan di bawah x-axis
    ax3.text(
        0.5,
        -0.2,
        "Interpretasi: S0 (Hijau) biasanya memiliki reward lebih tinggi karena buffer lebih sehat.\nS2 (Merah) menunjukkan performa saat kondisi kritis.",
        transform=ax3.transAxes,
        fontsize=10,
        ha="center",
        style="italic",
    )

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])

    # Simpan hasil
    os.makedirs(os.path.dirname(OUTPUT_CHART), exist_ok=True)
    plt.savefig(OUTPUT_CHART, dpi=300)
    print(f"Visualisasi berhasil disimpan di: {os.path.abspath(OUTPUT_CHART)}")


if __name__ == "__main__":
    plot_reward_analysis(REPORT_FILE)
