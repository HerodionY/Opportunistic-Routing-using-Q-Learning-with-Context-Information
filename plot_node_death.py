"""
plot_node_death_simple.py
=========================
Visualisasi NodeDeathReport yang disederhanakan (Light Theme).
Fokus pada:
  1. Kurva Kematian Node seiring waktu (Survival Curve).
  2. Total Node yang mati di akhir simulasi.
"""

import os
import pandas as pd
import matplotlib.pyplot as plt
import numpy as np

# =============================================================================
# CONFIG
# =============================================================================

REPORT_DIR = "hasil_skripsi/test_1/Helsinki/energy/test3"
SIM_DURATION = 43200
TOTAL_NODES = 200

FILES = {
    "ORQLCI": os.path.join(
        REPORT_DIR, "ORQLCI_ShortestMap_Optimized_NodeDeathReport.txt"
    ),
    "Epidemic": os.path.join(
        REPORT_DIR, "Epidemic_Helsinki_EnergyAware_NodeDeathReport.txt"
    ),
    "Prophet": os.path.join(
        REPORT_DIR, "Prophet_Helsinki_EnergyAware_NodeDeathReport.txt"
    ),
}

COLORS = {
    "ORQLCI": "#2ecc71",  # Hijau
    "Epidemic": "#e74c3c",  # Merah
    "Prophet": "#3498db",  # Biru
}

OUTPUT_FILE = os.path.join(REPORT_DIR, "node_death_visulization.png")


def load_death_report(filepath):
    if not os.path.exists(filepath):
        print(f"  [SKIP] File tidak ditemukan: {filepath}")
        return None

    rows = []
    with open(filepath, "r") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) < 6:
                continue
            try:
                rows.append(
                    {
                        "sim_time": float(parts[0]),
                        "node_addr": int(parts[1]),
                        "router_type": parts[2],
                        "event": parts[5],
                    }
                )
            except (ValueError, IndexError):
                continue

    if not rows:
        return pd.DataFrame(columns=["sim_time", "node_addr", "router_type", "event"])

    return pd.DataFrame(rows)


print("=== Loading Data ===")
data = {}
for protocol, filepath in FILES.items():
    df = load_death_report(filepath)
    if df is not None:
        data[protocol] = df
        dead_count = len(df[df["event"] == "DEAD"])
        print(f"  {protocol:10s}: {dead_count:3d} DEAD")

if not data:
    print("Tidak ada data untuk divisualisasikan.")
    exit(1)

plt.style.use("seaborn-v0_8-whitegrid")

fig, (ax_surv, ax_bar) = plt.subplots(
    1, 2, figsize=(14, 6), gridspec_kw={"width_ratios": [3, 1]}
)
fig.patch.set_facecolor("white")


def fmt_time(x, _):
    h = int(x // 3600)
    m = int((x % 3600) // 60)
    return f"{h}h{m:02d}m"


ax_surv.set_title("Grafik Sisa Node Aktif", fontsize=14, fontweight="bold", pad=15)
ax_surv.set_xlabel("Waktu Simulasi", fontsize=12)
ax_surv.set_ylabel("Jumlah Node Aktif", fontsize=12)
ax_surv.set_xlim(0, SIM_DURATION)
ax_surv.set_ylim(0, TOTAL_NODES + 5)
ax_surv.xaxis.set_major_formatter(plt.FuncFormatter(fmt_time))

for protocol, df in data.items():
    df_dead = df[df["event"] == "DEAD"]

    if df_dead.empty:
        times, alive = np.array([0, SIM_DURATION]), np.array([TOTAL_NODES, TOTAL_NODES])
    else:
        sorted_times = np.sort(df_dead["sim_time"].values)
        times = np.concatenate([[0], sorted_times, [SIM_DURATION]])
        deaths = np.arange(len(sorted_times) + 1)
        alive = TOTAL_NODES - np.concatenate([[0], deaths])

    ax_surv.step(
        times,
        alive,
        where="post",
        color=COLORS[protocol],
        linewidth=2.5,
        label=protocol,
    )

    ax_surv.text(
        SIM_DURATION - 500,
        alive[-1] + 3,
        f"{alive[-1]} aktif",
        color=COLORS[protocol],
        fontweight="bold",
        ha="right",
        va="bottom",
    )

ax_surv.axhline(TOTAL_NODES, color="gray", linewidth=1, linestyle="--", alpha=0.5)
ax_surv.legend(loc="lower left", fontsize=11, frameon=True, shadow=True)

ax_bar.set_title("Total Node Mati", fontsize=14, fontweight="bold", pad=15)
ax_bar.set_ylabel("Jumlah Node", fontsize=12)

protocols = list(data.keys())
dead_counts = [len(data[p][data[p]["event"] == "DEAD"]) for p in protocols]
x_pos = np.arange(len(protocols))

bars = ax_bar.bar(
    x_pos, dead_counts, color=[COLORS[p] for p in protocols], width=0.6, alpha=0.9
)

for bar, count in zip(bars, dead_counts):
    ax_bar.text(
        bar.get_x() + bar.get_width() / 2,
        bar.get_height() + 2,
        f"{count}",
        ha="center",
        va="bottom",
        fontsize=12,
        fontweight="bold",
    )

ax_bar.set_xticks(x_pos)
ax_bar.set_xticklabels(protocols, fontsize=12)
ax_bar.set_ylim(0, TOTAL_NODES + 10)
ax_bar.axhline(TOTAL_NODES, color="gray", linewidth=1, linestyle="--", alpha=0.5)
ax_bar.text(
    len(protocols) - 0.5,
    TOTAL_NODES + 2,
    f"Maksimal: {TOTAL_NODES}",
    color="gray",
    fontsize=10,
    ha="right",
)


plt.tight_layout()
plt.savefig(OUTPUT_FILE, dpi=300, bbox_inches="tight", facecolor="white")
print(f"\n[OK] Plot disimpan ke: {OUTPUT_FILE}")
plt.show()
