"""
plot_node_death_advanced.py
=========================
Visualisasi NodeDeathReport dengan fitur Zoom per Zona (Interval).
Fokus pada:
  1. Kurva Kematian Node keseluruhan & Total node mati.
  2. Analisis per Zona Waktu (Zoomed-in gap & Jumlah kematian per zona).
"""

import os
import pandas as pd
import matplotlib.pyplot as plt
import numpy as np

# =============================================================================
# CONFIG
# =============================================================================

REPORT_DIR = "baru/helsinki/4/"
SIM_DURATION = 43200
TOTAL_NODES = 200

# REPORT_DIR = "baru/haggle/"
# SIM_DURATION = 274883
# TOTAL_NODES = 41

# Konfigurasi Zona / Interval
NUM_ZONES = 4  # Anda bisa ganti jadi 4 atau 5 sesuai kebutuhan

FILES = {
    # "ORQLCI": os.path.join(
    #     REPORT_DIR, "ORQLCI_Helsinki_Final_v2_NodeDeathReport.txt"
    # ),
    "ORQLCI_Without_EF": os.path.join(
        REPORT_DIR, "ORQLCI_Helsinki_Final_v2_withoutEnergy_NodeDeathReport.txt"
    ),
    "Epidemic": os.path.join(
        REPORT_DIR, "Epidemic_Helsinki_EnergyAware_NodeDeathReport.txt"
    ),
    "Prophet": os.path.join(
        REPORT_DIR, "Prophet_Helsinki_EnergyAware_NodeDeathReport.txt"
    ),
}

# FILES = {
#     "ORQLCI": os.path.join(
#         REPORT_DIR, "ORQLCI_Haggle_Infocom5_Final_NodeDeathReport.txt"
#     ),
#     "ORQLCI_Without_EF": os.path.join(
#         REPORT_DIR, "ORQLCI_Haggle_Infocom5_Final-without_energy_NodeDeathReport.txt"
#     ),

#     "Epidemic": os.path.join(
#         REPORT_DIR, "Epidemic_Haggle_Infocom5_Baseline_NodeDeathReport.txt"
#     ),
#     "Prophet": os.path.join(
#         REPORT_DIR, "Prophet_Haggle_Infocom5_Baseline_NodeDeathReport.txt"
#     ),
# }


COLORS = {
    # "ORQLCI": "#2ecc71",  # Hijau
    "ORQLCI_Without_EF": "#f39c12",  # Oranye
    "Epidemic": "#e74c3c",  # Merah
    "Prophet": "#3498db",  # Biru
}

OUTPUT_FILE_MAIN = os.path.join(REPORT_DIR, "node_death_visulization_withoutEF.png")
OUTPUT_FILE_ZONES = os.path.join(REPORT_DIR, f"node_death_visulization_zones_{NUM_ZONES}.png")

# =============================================================================
# DATA LOADING
# =============================================================================

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
                rows.append({
                    "sim_time": float(parts[0]),
                    "node_addr": int(parts[1]),
                    "router_type": parts[2],
                    "event": parts[5],
                })
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
        print(f"  {protocol:20s}: {dead_count:3d} DEAD")

if not data:
    print("Tidak ada data untuk divisualisasikan.")
    exit(1)

plt.style.use("seaborn-v0_8-whitegrid")

def fmt_time(x, _=None):
    h = int(x // 3600)
    m = int((x % 3600) // 60)
    return f"{h}h{m:02d}m"

# =============================================================================
# VISUALISASI 1: FULL SIMULATION (Original Anda)
# =============================================================================
fig1, (ax_surv, ax_bar) = plt.subplots(1, 2, figsize=(14, 6), gridspec_kw={"width_ratios": [3, 1]})
fig1.patch.set_facecolor("white")

ax_surv.set_title("Grafik Sisa Node Aktif (Keseluruhan)", fontsize=14, fontweight="bold", pad=15)
ax_surv.set_xlabel("Waktu Simulasi", fontsize=12)
ax_surv.set_ylabel("Jumlah Node Aktif", fontsize=12)
ax_surv.set_xlim(0, SIM_DURATION)
ax_surv.set_ylim(0, TOTAL_NODES + 5)
ax_surv.xaxis.set_major_formatter(plt.FuncFormatter(fmt_time))

protocols = list(data.keys())

for protocol in protocols:
    df = data[protocol]
    df_dead = df[df["event"] == "DEAD"]
    if df_dead.empty:
        times, alive = np.array([0, SIM_DURATION]), np.array([TOTAL_NODES, TOTAL_NODES])
    else:
        sorted_times = np.sort(df_dead["sim_time"].values)
        times = np.concatenate([[0], sorted_times, [SIM_DURATION]])
        deaths = np.arange(len(sorted_times) + 1)
        alive = TOTAL_NODES - np.concatenate([[0], deaths])

    ax_surv.step(times, alive, where="post", color=COLORS[protocol], linewidth=2.5, label=protocol)

ax_surv.axhline(TOTAL_NODES, color="gray", linewidth=1, linestyle="--", alpha=0.5)
ax_surv.legend(loc="lower left", fontsize=11, frameon=True, shadow=True)

# Bar Chart Keseluruhan
ax_bar.set_title("Total Node Mati", fontsize=14, fontweight="bold", pad=15)
dead_counts = [len(data[p][data[p]["event"] == "DEAD"]) for p in protocols]
x_pos = np.arange(len(protocols))
bars = ax_bar.bar(x_pos, dead_counts, color=[COLORS[p] for p in protocols], width=0.6, alpha=0.9)

for bar, count in zip(bars, dead_counts):
    ax_bar.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 2, f"{count}", ha="center", va="bottom", fontsize=12, fontweight="bold")

ax_bar.set_xticks(x_pos)
ax_bar.set_xticklabels(protocols, fontsize=10, rotation=20, ha="right")
ax_bar.set_ylim(0, TOTAL_NODES + 10)

plt.tight_layout()
fig1.savefig(OUTPUT_FILE_MAIN, dpi=300, bbox_inches="tight", facecolor="white")


# =============================================================================
# VISUALISASI 2: INTERVAL / ZONE ANALYSIS (FITUR BARU)
# =============================================================================
interval_size = SIM_DURATION / NUM_ZONES

# Buat Figure dengan 2 Baris: 
# Baris 1: Zoomed-in Curve per Zona, Baris 2: Bar chart kematian di zona tersebut
fig2, axes = plt.subplots(2, NUM_ZONES, figsize=(4.5 * NUM_ZONES, 10))
fig2.patch.set_facecolor("white")
fig2.suptitle(f"Analisis Pola Kematian Node per Zona Waktu (Dibagi {NUM_ZONES} Fase)", fontsize=18, fontweight="bold", y=1.02)

# Siapkan array posisi X untuk bar chart
bar_width = 0.35
x_pos_zone = np.arange(len(protocols))

for i in range(NUM_ZONES):
    t_start = i * interval_size
    t_end = (i + 1) * interval_size
    
    ax_zoom = axes[0, i] # Axis untuk Survival Curve
    ax_bar_zone = axes[1, i] # Axis untuk Bar Chart
    
    # --- 1. PLOT ZOOMED SURVIVAL CURVE ---
    ax_zoom.set_title(f"Zona {i+1}\n({fmt_time(t_start)} - {fmt_time(t_end)})", fontsize=12, fontweight="bold")
    ax_zoom.set_xlim(t_start, t_end)
    ax_zoom.xaxis.set_major_formatter(plt.FuncFormatter(fmt_time))
    if i == 0:
        ax_zoom.set_ylabel("Node Aktif (Zoomed)", fontsize=11)
        
    zone_min_alive = TOTAL_NODES
    zone_max_alive = 0
    
    zone_deaths_counts = [] # Simpan untuk bar chart
    
    for protocol in protocols:
        df = data[protocol]
        df_dead = df[df["event"] == "DEAD"]
        
        # Hitung jumlah kematian HANYA di zona waktu ini
        deaths_in_zone = len(df_dead[(df_dead["sim_time"] > t_start) & (df_dead["sim_time"] <= t_end)])
        zone_deaths_counts.append(deaths_in_zone)
        
        # Data untuk line chart
        if df_dead.empty:
            times, alive = np.array([0, SIM_DURATION]), np.array([TOTAL_NODES, TOTAL_NODES])
        else:
            sorted_times = np.sort(df_dead["sim_time"].values)
            times = np.concatenate([[0], sorted_times, [SIM_DURATION]])
            deaths = np.arange(len(sorted_times) + 1)
            alive = TOTAL_NODES - np.concatenate([[0], deaths])
            
        ax_zoom.step(times, alive, where="post", color=COLORS[protocol], linewidth=2.5, label=protocol)
        
        # Hitung y-limit agar auto-zoom ke data yang relevan di window ini
        alive_at_start = TOTAL_NODES - len(df_dead[df_dead['sim_time'] <= t_start])
        alive_at_end = TOTAL_NODES - len(df_dead[df_dead['sim_time'] <= t_end])
        
        zone_max_alive = max(zone_max_alive, alive_at_start)
        zone_min_alive = min(zone_min_alive, alive_at_end)

    # Set custom Y-limits untuk ZOOM (Berikan padding sedikit)
    ax_zoom.set_ylim(max(0, zone_min_alive - 2), min(TOTAL_NODES + 2, zone_max_alive + 2))
    
    # --- 2. PLOT BAR CHART KEMATIAN PER ZONA ---
    bars_zone = ax_bar_zone.bar(x_pos_zone, zone_deaths_counts, color=[COLORS[p] for p in protocols], width=0.6, alpha=0.9)
    
    for bar, count in zip(bars_zone, zone_deaths_counts):
        ax_bar_zone.text(bar.get_x() + bar.get_width()/2, bar.get_height() + 0.1, 
                         f"{count}", ha="center", va="bottom", fontsize=11, fontweight="bold")

    ax_bar_zone.set_xticks(x_pos_zone)
    ax_bar_zone.set_xticklabels(["" for _ in protocols]) # Sembunyikan label di tengah agar rapi
    if i == 0:
        ax_bar_zone.set_ylabel("Node Mati di Zona Ini", fontsize=11)
        
    # Tambah legenda hanya di plot pojok
    if i == NUM_ZONES - 1:
        ax_zoom.legend(loc="upper right", fontsize=9)
        ax_bar_zone.set_xticks(x_pos_zone)
        ax_bar_zone.set_xticklabels(protocols, rotation=20, ha="right", fontsize=9)

plt.tight_layout()
fig2.savefig(OUTPUT_FILE_ZONES, dpi=300, bbox_inches="tight", facecolor="white")

print(f"\n[OK] Plot Keseluruhan disimpan ke : {OUTPUT_FILE_MAIN}")
print(f"[OK] Plot Zona Interval disimpan ke : {OUTPUT_FILE_ZONES}")
plt.show()