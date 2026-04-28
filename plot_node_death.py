"""
plot_node_death.py
==================
Visualisasi NodeDeathReport untuk perbandingan:
  - ORQLCI (CCRouting)
  - Epidemic (EpidemicEnergyRouter)
  - Prophet  (ProphetEnergyRouter)

Output: plot PNG di direktori yang sama dengan file report.

Jalankan dari root project:
    python plot_node_death.py
"""

import os
import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.patches as mpatches
import numpy as np

# =============================================================================
# CONFIG
# =============================================================================

REPORT_DIR   = "hasil_skripsi/test_1/Helsinki/energy"
SIM_DURATION = 43200   # detik (12 jam)
TOTAL_NODES  = 200

# File report (sesuaikan Scenario.name di config masing-masing)
FILES = {
    "ORQLCI"   : os.path.join(REPORT_DIR, "ORQLCI_ShortestMap_Optimized_NodeDeathReport.txt"),
    "Epidemic" : os.path.join(REPORT_DIR, "Epidemic_Helsinki_EnergyAware_NodeDeathReport.txt"),
    "Prophet"  : os.path.join(REPORT_DIR, "Prophet_Helsinki_EnergyAware_NodeDeathReport.txt"),
}

# Palet warna konsisten
COLORS = {
    "ORQLCI"   : "#2ecc71",   # hijau
    "Epidemic" : "#e74c3c",   # merah
    "Prophet"  : "#3498db",   # biru
}

HARD_GATE_COLOR = "#f39c12"   # oranye — untuk event HARD_GATE ORQLCI

OUTPUT_FILE = os.path.join(REPORT_DIR, "node_death_comparison.png")

# =============================================================================
# LOAD DATA
# =============================================================================

def load_death_report(filepath):
    """
    Load NodeDeathReport TSV.
    Return DataFrame dengan kolom:
        sim_time, node_addr, router_type, max_energy, energy_ratio, event
    Return None jika file tidak ada atau kosong.
    """
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
                rows.append({
                    "sim_time"    : float(parts[0]),
                    "node_addr"   : int(parts[1]),
                    "router_type" : parts[2],
                    "max_energy"  : float(parts[3]),
                    "energy_ratio": float(parts[4]),
                    "event"       : parts[5],
                })
            except (ValueError, IndexError):
                continue

    if not rows:
        print(f"  [EMPTY] File ada tapi tidak ada data event: {filepath}")
        return None

    return pd.DataFrame(rows)


print("=== Loading NodeDeathReport files ===")
data = {}
for protocol, filepath in FILES.items():
    df = load_death_report(filepath)
    if df is not None:
        data[protocol] = df
        dead  = len(df[df["event"] == "DEAD"])
        hgate = len(df[df["event"] == "HARD_GATE"])
        print(f"  {protocol:10s}: {dead:3d} DEAD, {hgate:3d} HARD_GATE")

if not data:
    print("Tidak ada data untuk divisualisasikan. Pastikan simulasi sudah selesai.")
    exit(1)

# =============================================================================
# HELPER: Survival curve
# =============================================================================

def survival_curve(df_dead, sim_duration=SIM_DURATION, total_nodes=TOTAL_NODES):
    """
    Hitung jumlah node HIDUP dari waktu ke waktu.
    Returns: (time_points, alive_counts)
    """
    if df_dead is None or df_dead.empty:
        return np.array([0, sim_duration]), np.array([total_nodes, total_nodes])

    sorted_times = np.sort(df_dead["sim_time"].values)
    times  = np.concatenate([[0], sorted_times, [sim_duration]])
    deaths = np.arange(len(sorted_times) + 1)   # 0, 1, 2, ...
    alive  = total_nodes - np.concatenate([[0], deaths])

    return times, alive

# =============================================================================
# PLOT
# =============================================================================

fig = plt.figure(figsize=(18, 14))
fig.patch.set_facecolor("#1a1a2e")

ax_title = fig.add_axes([0, 0.95, 1, 0.05])
ax_title.axis("off")
ax_title.text(0.5, 0.5,
    "Node Energy Death Analysis — Helsinki Simulation (12 Hours)",
    ha="center", va="center", color="white",
    fontsize=18, fontweight="bold",
    fontfamily="monospace")

# Layout 2x2 + 1 row bawah
gs = fig.add_gridspec(3, 2, hspace=0.45, wspace=0.3,
                      left=0.07, right=0.97, top=0.93, bottom=0.06)

ax_surv   = fig.add_subplot(gs[0, :])   # Survival curve — full width
ax_hist   = fig.add_subplot(gs[1, 0])   # Death histogram
ax_cum    = fig.add_subplot(gs[1, 1])   # Cumulative deaths
ax_hgate  = fig.add_subplot(gs[2, 0])   # ORQLCI hard gate vs dead
ax_bar    = fig.add_subplot(gs[2, 1])   # Summary bar

PANEL_BG  = "#16213e"
TEXT_COL  = "#e0e0e0"
GRID_COL  = "#2a2a4a"

for ax in [ax_surv, ax_hist, ax_cum, ax_hgate, ax_bar]:
    ax.set_facecolor(PANEL_BG)
    ax.tick_params(colors=TEXT_COL, labelsize=9)
    ax.xaxis.label.set_color(TEXT_COL)
    ax.yaxis.label.set_color(TEXT_COL)
    ax.title.set_color(TEXT_COL)
    for spine in ax.spines.values():
        spine.set_edgecolor(GRID_COL)
    ax.grid(True, color=GRID_COL, linewidth=0.5, alpha=0.7)

def fmt_time(x, _):
    """Format detik ke jam:menit"""
    h = int(x // 3600)
    m = int((x % 3600) // 60)
    return f"{h}h{m:02d}m"

# ---------------------------------------------------------------------------
# 1. SURVIVAL CURVE (node hidup dari waktu ke waktu)
# ---------------------------------------------------------------------------
ax_surv.set_title("Node Survival Over Time (% Alive)", fontsize=13, fontweight="bold", pad=8)
ax_surv.set_xlabel("Simulation Time", fontsize=10)
ax_surv.set_ylabel("Nodes Alive", fontsize=10)
ax_surv.set_xlim(0, SIM_DURATION)
ax_surv.set_ylim(0, TOTAL_NODES + 5)
ax_surv.xaxis.set_major_formatter(plt.FuncFormatter(fmt_time))

for protocol, df in data.items():
    df_dead = df[df["event"] == "DEAD"]
    times, alive = survival_curve(df_dead)
    ax_surv.step(times, alive, where="post",
                 color=COLORS[protocol], linewidth=2.5, label=protocol)
    # Annotate final alive count
    final_alive = alive[-1]
    ax_surv.annotate(f"{final_alive} alive",
                     xy=(SIM_DURATION, final_alive),
                     xytext=(-60, 5), textcoords="offset points",
                     color=COLORS[protocol], fontsize=8, fontweight="bold",
                     arrowprops=dict(arrowstyle="->", color=COLORS[protocol], lw=1))

ax_surv.axhline(TOTAL_NODES, color="white", linewidth=0.8, linestyle="--", alpha=0.4)
ax_surv.legend(loc="upper right", facecolor="#0f3460", edgecolor=GRID_COL,
               labelcolor=TEXT_COL, fontsize=10)

# ---------------------------------------------------------------------------
# 2. DEATH HISTOGRAM (berapa banyak node mati per 30-menit window)
# ---------------------------------------------------------------------------
ax_hist.set_title("Death Events per 30-Minute Window", fontsize=11, fontweight="bold", pad=6)
ax_hist.set_xlabel("Simulation Time", fontsize=9)
ax_hist.set_ylabel("Nodes Died", fontsize=9)
ax_hist.xaxis.set_major_formatter(plt.FuncFormatter(fmt_time))

bins = np.arange(0, SIM_DURATION + 1800, 1800)   # 30-menit bins
width = 1800 / (len(data) + 1)
offsets = np.linspace(-width * (len(data)-1)/2, width * (len(data)-1)/2, len(data))

for i, (protocol, df) in enumerate(data.items()):
    df_dead = df[df["event"] == "DEAD"]
    if df_dead.empty:
        continue
    counts, _ = np.histogram(df_dead["sim_time"].values, bins=bins)
    centers = (bins[:-1] + bins[1:]) / 2
    ax_hist.bar(centers + offsets[i], counts, width=width * 0.85,
                color=COLORS[protocol], alpha=0.85, label=protocol, edgecolor="none")

ax_hist.legend(loc="upper right", facecolor="#0f3460", edgecolor=GRID_COL,
               labelcolor=TEXT_COL, fontsize=8)

# ---------------------------------------------------------------------------
# 3. CUMULATIVE DEATHS
# ---------------------------------------------------------------------------
ax_cum.set_title("Cumulative Dead Nodes Over Time", fontsize=11, fontweight="bold", pad=6)
ax_cum.set_xlabel("Simulation Time", fontsize=9)
ax_cum.set_ylabel("Total Dead Nodes", fontsize=9)
ax_cum.set_xlim(0, SIM_DURATION)
ax_cum.set_ylim(0, TOTAL_NODES + 5)
ax_cum.xaxis.set_major_formatter(plt.FuncFormatter(fmt_time))
ax_cum.axhline(TOTAL_NODES, color="white", linewidth=0.6, linestyle="--", alpha=0.3,
               label="All 200 nodes")

for protocol, df in data.items():
    df_dead = df[df["event"] == "DEAD"].sort_values("sim_time")
    if df_dead.empty:
        continue
    times = df_dead["sim_time"].values
    cumulative = np.arange(1, len(times) + 1)
    ax_cum.step(np.concatenate([[0], times]), np.concatenate([[0], cumulative]),
                where="post", color=COLORS[protocol], linewidth=2, label=protocol)

ax_cum.legend(loc="upper left", facecolor="#0f3460", edgecolor=GRID_COL,
              labelcolor=TEXT_COL, fontsize=8)

# ---------------------------------------------------------------------------
# 4. ORQLCI: HARD_GATE vs DEAD timeline
# ---------------------------------------------------------------------------
ax_hgate.set_title("ORQLCI Node States Over Time\n(Hard Gate ≤4% vs Dead =0%)", fontsize=10, fontweight="bold", pad=6)
ax_hgate.set_xlabel("Simulation Time", fontsize=9)
ax_hgate.set_ylabel("Node Address", fontsize=9)
ax_hgate.xaxis.set_major_formatter(plt.FuncFormatter(fmt_time))

if "ORQLCI" in data:
    df_orqlci = data["ORQLCI"]
    df_hg = df_orqlci[df_orqlci["event"] == "HARD_GATE"]
    df_dead = df_orqlci[df_orqlci["event"] == "DEAD"]

    if not df_hg.empty:
        ax_hgate.scatter(df_hg["sim_time"], df_hg["node_addr"],
                         color=HARD_GATE_COLOR, s=25, alpha=0.8,
                         marker="^", label=f"Hard Gate ({len(df_hg)} nodes)", zorder=3)
    if not df_dead.empty:
        ax_hgate.scatter(df_dead["sim_time"], df_dead["node_addr"],
                         color=COLORS["ORQLCI"], s=30, alpha=0.9,
                         marker="x", label=f"Dead ({len(df_dead)} nodes)", zorder=4)

    if df_hg.empty and df_dead.empty:
        ax_hgate.text(0.5, 0.5, "No death events\n(All nodes survived!)",
                      ha="center", va="center", color=COLORS["ORQLCI"],
                      fontsize=14, fontweight="bold",
                      transform=ax_hgate.transAxes)
else:
    ax_hgate.text(0.5, 0.5, "ORQLCI data\nnot available yet",
                  ha="center", va="center", color=TEXT_COL, fontsize=12,
                  transform=ax_hgate.transAxes)

    handles, labels = ax_hgate.get_legend_handles_labels()
    if handles:
        ax_hgate.legend(handles, labels, loc="upper right",
                        facecolor="#0f3460", edgecolor=GRID_COL,
                        labelcolor=TEXT_COL, fontsize=8)

# ---------------------------------------------------------------------------
# 5. SUMMARY BAR CHART
# ---------------------------------------------------------------------------
ax_bar.set_title("Total Dead Nodes at End of Simulation", fontsize=11, fontweight="bold", pad=6)
ax_bar.set_ylabel("Dead Nodes (out of 200)", fontsize=9)

protocols = list(data.keys())
dead_counts = []
hgate_counts = []
for p in protocols:
    df = data[p]
    dead_counts.append(len(df[df["event"] == "DEAD"]))
    hgate_counts.append(len(df[df["event"] == "HARD_GATE"]))

x = np.arange(len(protocols))
bars = ax_bar.bar(x, dead_counts,
                  color=[COLORS[p] for p in protocols],
                  edgecolor="none", alpha=0.9, width=0.5)

# HARD_GATE overlay untuk ORQLCI
for i, (p, hg) in enumerate(zip(protocols, hgate_counts)):
    if hg > 0:
        ax_bar.bar(x[i], hg, color=HARD_GATE_COLOR, alpha=0.7,
                   width=0.5, bottom=dead_counts[i],
                   label=f"Hard Gate ({hg})")

# Value labels
for bar, count in zip(bars, dead_counts):
    pct = count / TOTAL_NODES * 100
    ax_bar.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 2,
                f"{count}\n({pct:.0f}%)",
                ha="center", va="bottom", color=TEXT_COL,
                fontsize=10, fontweight="bold")

ax_bar.set_xticks(x)
ax_bar.set_xticklabels(protocols, color=TEXT_COL, fontsize=11, fontweight="bold")
ax_bar.set_ylim(0, TOTAL_NODES + 25)
ax_bar.axhline(TOTAL_NODES, color="white", linewidth=0.8, linestyle="--", alpha=0.5)
ax_bar.text(len(protocols) - 0.5, TOTAL_NODES + 2, "200 total nodes",
            color=TEXT_COL, fontsize=7, alpha=0.7)

# HARD_GATE legend jika ada
hgate_patch = mpatches.Patch(color=HARD_GATE_COLOR, alpha=0.7, label="Hard Gate entries")
total_hg = sum(hgate_counts)
if total_hg > 0:
    ax_bar.legend(handles=[hgate_patch], facecolor="#0f3460", edgecolor=GRID_COL,
                  labelcolor=TEXT_COL, fontsize=8)

# =============================================================================
# SAVE & SHOW
# =============================================================================

plt.savefig(OUTPUT_FILE, dpi=150, bbox_inches="tight",
            facecolor=fig.get_facecolor())
print(f"\n[OK] Plot disimpan ke: {OUTPUT_FILE}")
plt.show()
