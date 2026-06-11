"""
Visualisasi hasil iter05 — config terbaik dari 10-iterasi sweep.

Config:
    initialEnergy   = 2500
    epsilonStart    = 0.40
    transmitEnergy  = 3.0
    receiveEnergy   = 1.0

Result:
    WithEF deaths    = 166  (34 survivors)
    WithoutEF deaths = 174  (26 survivors)
    Selisih          = +8 nodes selamat (30% improvement in survival rate)
    Delivery prob    : EF=0.6412, NoEF=0.6503
    Avg latency      : EF=6415.84s, NoEF=6462.72s

Output: 5 chart PNG di folder visualizations/iter05/
    1. living_nodes_over_time.png      — main narrative chart
    2. cumulative_deaths.png            — step-curve cascade comparison
    3. deaths_by_node_type.png          — which node types EF saves
    4. death_timeline_scatter.png       — death event distribution
    5. performance_metrics.png          — overall metrics bar chart

Usage:
    python visualize_iter05.py
"""

from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np

# ============================================================================
# PATHS & CONSTANTS
# ============================================================================

PROJECT_DIR = Path(__file__).resolve().parent
ITER_DIR = PROJECT_DIR / "Z_iterations" / "iter05_lower_transmit_3.0"
WITHEF_DIR = ITER_DIR / "withEF"
WITHOUTEF_DIR = ITER_DIR / "withoutEF"
OUTPUT_DIR = PROJECT_DIR / "visualizations" / "iter05"

SIM_END = 43200            # 12 hours in seconds
TOTAL_NODES = 200

# Helsinki dataset node groups (from config)
NODE_TYPES = {
    "Pedestrian (p)": (0, 64),       # 64 nodes
    "Cyclist (c)":    (64, 128),     # 64 nodes
    "Worker (w)":     (128, 194),    # 66 nodes
    "Tram (t)":       (194, 200),    # 6 nodes
}
NODE_TYPE_COLORS = {
    "Pedestrian (p)": "#3498db",     # blue
    "Cyclist (c)":    "#e74c3c",     # red
    "Worker (w)":     "#2ecc71",     # green
    "Tram (t)":       "#f39c12",     # orange
}

# Comparison colors (consistent across all charts)
COLOR_EF = "#27ae60"        # green = WithEF (good)
COLOR_NOEF = "#c0392b"      # dark red = WithoutEF (worse)

# Config used (for chart titles)
CONFIG_LABEL = "iter05: E=2500, ε=0.40, TX=3.0, RX=1.0"


# ============================================================================
# PARSING FUNCTIONS
# ============================================================================

def parse_node_deaths(filepath):
    """Parse NodeDeathReport file. Returns sorted list of dicts."""
    deaths = []
    with open(filepath, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) >= 6 and parts[-1] == "DEAD":
                try:
                    deaths.append({
                        "time": float(parts[0]),
                        "addr": int(parts[1]),
                        "max_energy": float(parts[3]),
                    })
                except ValueError:
                    continue
    return sorted(deaths, key=lambda d: d["time"])


def parse_message_stats(filepath):
    """Parse MessageStatsReport file. Returns dict of metrics."""
    stats = {}
    with open(filepath, "r", encoding="utf-8") as f:
        for line in f:
            if ":" not in line:
                continue
            key, _, value = line.partition(":")
            try:
                stats[key.strip()] = float(value.strip())
            except ValueError:
                pass
    return stats


def get_node_type(addr):
    """Map node address to type name."""
    for type_name, (start, end) in NODE_TYPES.items():
        if start <= addr < end:
            return type_name
    return "Unknown"


# ============================================================================
# CHART 1: LIVING NODES OVER TIME (Main Narrative)
# ============================================================================

def plot_living_nodes(ef_deaths, noef_deaths, output_path):
    """Living nodes line chart — the headline visualization."""
    times = np.linspace(0, SIM_END, 2000)
    ef_times_sorted = sorted([d["time"] for d in ef_deaths])
    noef_times_sorted = sorted([d["time"] for d in noef_deaths])

    ef_alive = np.array([TOTAL_NODES - np.searchsorted(ef_times_sorted, t, side="right")
                          for t in times])
    noef_alive = np.array([TOTAL_NODES - np.searchsorted(noef_times_sorted, t, side="right")
                            for t in times])

    times_hours = times / 3600.0

    fig, ax = plt.subplots(figsize=(14, 8))

    # Fill the advantage area first (so lines render on top)
    ax.fill_between(times_hours, noef_alive, ef_alive,
                     where=(ef_alive >= noef_alive),
                     color=COLOR_EF, alpha=0.20, interpolate=True,
                     label="EF advantage area")

    ax.plot(times_hours, noef_alive, color=COLOR_NOEF, linewidth=2.8,
            label=f"Without EF ({len(noef_deaths)} deaths, {TOTAL_NODES - len(noef_deaths)} survivors)")
    ax.plot(times_hours, ef_alive, color=COLOR_EF, linewidth=2.8,
            label=f"With EF ({len(ef_deaths)} deaths, {TOTAL_NODES - len(ef_deaths)} survivors)")

    # Annotation showing final advantage
    final_diff = ef_alive[-1] - noef_alive[-1]
    if final_diff > 0:
        ax.annotate(
            f"+{final_diff} more survivors\n with EF",
            xy=(SIM_END / 3600, ef_alive[-1]),
            xytext=(SIM_END / 3600 - 1.5, ef_alive[-1] + 15),
            fontsize=13, fontweight="bold", color=COLOR_EF,
            arrowprops=dict(arrowstyle="->", color=COLOR_EF, lw=2),
            bbox=dict(boxstyle="round,pad=0.5",
                      facecolor="lightyellow", edgecolor=COLOR_EF, linewidth=1.5),
        )

    ax.set_xlabel("Simulation Time (hours)", fontsize=13)
    ax.set_ylabel("Number of Living Nodes", fontsize=13)
    ax.set_title(f"Node Survival Comparison: With EF vs Without EF\n({CONFIG_LABEL})",
                 fontsize=14, fontweight="bold")
    ax.legend(loc="lower left", fontsize=11, framealpha=0.95)
    ax.grid(True, alpha=0.3)
    ax.set_xlim(0, SIM_END / 3600)
    ax.set_ylim(0, TOTAL_NODES + 10)
    ax.axhline(y=TOTAL_NODES, color="gray", linestyle=":", alpha=0.5)
    ax.text(0.5, TOTAL_NODES + 2, f"Total nodes: {TOTAL_NODES}",
            fontsize=10, color="gray")

    plt.tight_layout()
    plt.savefig(output_path, dpi=150, bbox_inches="tight")
    plt.close()
    print(f"  [1/5] Saved: {output_path.name}")


# ============================================================================
# CHART 2: CUMULATIVE DEATHS (Step Curve)
# ============================================================================

def plot_cumulative_deaths(ef_deaths, noef_deaths, output_path):
    """Step curve showing when deaths happen — emphasizes cascade dynamics."""
    ef_hours = [d["time"] / 3600 for d in ef_deaths]
    noef_hours = [d["time"] / 3600 for d in noef_deaths]

    fig, ax = plt.subplots(figsize=(14, 8))

    ax.step([0] + noef_hours + [SIM_END / 3600],
            [0] + list(range(1, len(noef_hours) + 1)) + [len(noef_hours)],
            where="post", color=COLOR_NOEF, linewidth=2.5,
            label=f"Without EF ({len(noef_deaths)} total deaths)")
    ax.step([0] + ef_hours + [SIM_END / 3600],
            [0] + list(range(1, len(ef_hours) + 1)) + [len(ef_hours)],
            where="post", color=COLOR_EF, linewidth=2.5,
            label=f"With EF ({len(ef_deaths)} total deaths)")

    # Mark first death of each
    if ef_hours:
        ax.axvline(x=ef_hours[0], color=COLOR_EF, linestyle="--", alpha=0.5)
        ax.text(ef_hours[0] + 0.1, 5, f"EF first death\n@ {ef_hours[0]:.1f}h",
                color=COLOR_EF, fontsize=9)
    if noef_hours:
        ax.axvline(x=noef_hours[0], color=COLOR_NOEF, linestyle="--", alpha=0.5)
        ax.text(noef_hours[0] + 0.1, 15, f"NoEF first death\n@ {noef_hours[0]:.1f}h",
                color=COLOR_NOEF, fontsize=9)

    ax.set_xlabel("Simulation Time (hours)", fontsize=13)
    ax.set_ylabel("Cumulative Death Count", fontsize=13)
    ax.set_title(f"Cumulative Death Curve\n({CONFIG_LABEL})",
                 fontsize=14, fontweight="bold")
    ax.legend(loc="upper left", fontsize=12)
    ax.grid(True, alpha=0.3)
    ax.set_xlim(0, SIM_END / 3600)
    ax.set_ylim(0, max(len(ef_deaths), len(noef_deaths)) + 10)

    plt.tight_layout()
    plt.savefig(output_path, dpi=150, bbox_inches="tight")
    plt.close()
    print(f"  [2/5] Saved: {output_path.name}")


# ============================================================================
# CHART 3: DEATHS BY NODE TYPE
# ============================================================================

def plot_deaths_by_type(ef_deaths, noef_deaths, output_path):
    """Grouped bar chart: deaths per node type."""
    types_order = list(NODE_TYPES.keys())
    type_totals = {t: end - start for t, (start, end) in NODE_TYPES.items()}

    ef_counts = {t: 0 for t in types_order}
    noef_counts = {t: 0 for t in types_order}

    for d in ef_deaths:
        nt = get_node_type(d["addr"])
        if nt in ef_counts:
            ef_counts[nt] += 1
    for d in noef_deaths:
        nt = get_node_type(d["addr"])
        if nt in noef_counts:
            noef_counts[nt] += 1

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(16, 7))

    x = np.arange(len(types_order))
    width = 0.36

    # --- Left: absolute counts ---
    bars1 = ax1.bar(x - width / 2, [noef_counts[t] for t in types_order], width,
                     label="Without EF", color=COLOR_NOEF, alpha=0.88,
                     edgecolor="black", linewidth=0.8)
    bars2 = ax1.bar(x + width / 2, [ef_counts[t] for t in types_order], width,
                     label="With EF", color=COLOR_EF, alpha=0.88,
                     edgecolor="black", linewidth=0.8)

    # Labels on bars
    for bars in (bars1, bars2):
        for bar in bars:
            h = bar.get_height()
            ax1.text(bar.get_x() + bar.get_width() / 2., h + 0.5,
                     f"{int(h)}", ha="center", va="bottom", fontsize=10, fontweight="bold")

    # Total node markers
    for i, t in enumerate(types_order):
        ax1.hlines(type_totals[t], i - 0.4, i + 0.4,
                   colors="gray", linestyles=":", linewidth=2, alpha=0.6)

    ax1.set_xticks(x)
    ax1.set_xticklabels([t.split(" ")[0] for t in types_order], fontsize=11)
    ax1.set_ylabel("Number of Deaths", fontsize=12)
    ax1.set_title("Deaths by Node Type — Absolute Count\n(dashed lines = total nodes per type)",
                  fontsize=13, fontweight="bold")
    ax1.legend(fontsize=11)
    ax1.grid(True, alpha=0.3, axis="y")

    # --- Right: percentage ---
    noef_pct = [noef_counts[t] / type_totals[t] * 100 for t in types_order]
    ef_pct = [ef_counts[t] / type_totals[t] * 100 for t in types_order]

    bars3 = ax2.bar(x - width / 2, noef_pct, width,
                     label="Without EF", color=COLOR_NOEF, alpha=0.88,
                     edgecolor="black", linewidth=0.8)
    bars4 = ax2.bar(x + width / 2, ef_pct, width,
                     label="With EF", color=COLOR_EF, alpha=0.88,
                     edgecolor="black", linewidth=0.8)

    for bars in (bars3, bars4):
        for bar in bars:
            h = bar.get_height()
            ax2.text(bar.get_x() + bar.get_width() / 2., h + 1.0,
                     f"{h:.1f}%", ha="center", va="bottom", fontsize=10, fontweight="bold")

    ax2.axhline(y=100, color="gray", linestyle=":", alpha=0.5)
    ax2.set_xticks(x)
    ax2.set_xticklabels([f"{t.split(' ')[0]}\n(n={type_totals[t]})" for t in types_order],
                        fontsize=10)
    ax2.set_ylabel("Death Rate (%)", fontsize=12)
    ax2.set_title("Deaths by Node Type — Percentage", fontsize=13, fontweight="bold")
    ax2.legend(fontsize=11)
    ax2.grid(True, alpha=0.3, axis="y")
    ax2.set_ylim(0, 115)

    fig.suptitle(f"Node Type Mortality Analysis  ({CONFIG_LABEL})",
                 fontsize=14, fontweight="bold", y=1.02)
    plt.tight_layout()
    plt.savefig(output_path, dpi=150, bbox_inches="tight")
    plt.close()
    print(f"  [3/5] Saved: {output_path.name}")


# ============================================================================
# CHART 4: DEATH TIMELINE SCATTER
# ============================================================================

def plot_death_timeline(ef_deaths, noef_deaths, output_path):
    """Scatter: time vs node address, colored by type."""
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(17, 8), sharey=True, sharex=True)

    for ax, deaths, title in [
        (ax1, ef_deaths, f"With EF — {len(ef_deaths)} deaths"),
        (ax2, noef_deaths, f"Without EF — {len(noef_deaths)} deaths"),
    ]:
        # Background bands per node type
        for type_name, (start, end) in NODE_TYPES.items():
            ax.axhspan(start, end, alpha=0.07, color=NODE_TYPE_COLORS[type_name])

        # Scatter points
        for type_name, color in NODE_TYPE_COLORS.items():
            xs = [d["time"] / 3600 for d in deaths if get_node_type(d["addr"]) == type_name]
            ys = [d["addr"] for d in deaths if get_node_type(d["addr"]) == type_name]
            ax.scatter(xs, ys, c=color, label=type_name,
                       alpha=0.75, s=60, edgecolors="black", linewidths=0.5)

        ax.set_xlabel("Death Time (hours)", fontsize=12)
        ax.set_title(title, fontsize=13, fontweight="bold")
        ax.grid(True, alpha=0.3)
        ax.set_xlim(0, SIM_END / 3600)
        ax.set_ylim(-5, TOTAL_NODES + 5)

    ax1.set_ylabel("Node Address", fontsize=12)
    ax1.legend(loc="upper left", fontsize=10, framealpha=0.95)

    fig.suptitle(f"Death Event Timeline by Node Address  ({CONFIG_LABEL})",
                 fontsize=14, fontweight="bold")
    plt.tight_layout()
    plt.savefig(output_path, dpi=150, bbox_inches="tight")
    plt.close()
    print(f"  [4/5] Saved: {output_path.name}")


# ============================================================================
# CHART 5: PERFORMANCE METRICS COMPARISON
# ============================================================================

def plot_performance_metrics(ef_stats, noef_stats, ef_deaths, noef_deaths, output_path):
    """5-panel bar chart of all key metrics."""
    metrics = [
        ("Survivors", TOTAL_NODES - len(ef_deaths), TOTAL_NODES - len(noef_deaths), "{:.0f}", ""),
        ("Delivery Prob", ef_stats.get("delivery_prob", 0) * 100,
         noef_stats.get("delivery_prob", 0) * 100, "{:.2f}", "%"),
        ("Avg Latency", ef_stats.get("latency_avg", 0) / 3600,
         noef_stats.get("latency_avg", 0) / 3600, "{:.2f}", "h"),
        ("Overhead Ratio", ef_stats.get("overhead_ratio", 0),
         noef_stats.get("overhead_ratio", 0), "{:.2f}", ""),
        ("Avg Hopcount", ef_stats.get("hopcount_avg", 0),
         noef_stats.get("hopcount_avg", 0), "{:.2f}", ""),
    ]

    fig, axes = plt.subplots(1, 5, figsize=(20, 6))

    for ax, (name, ef_val, noef_val, fmt, unit) in zip(axes, metrics):
        bars = ax.bar(["Without\nEF", "With\nEF"], [noef_val, ef_val],
                       color=[COLOR_NOEF, COLOR_EF], alpha=0.88,
                       edgecolor="black", linewidth=1.2, width=0.55)

        for bar in bars:
            h = bar.get_height()
            label = fmt.format(h) + unit
            ax.text(bar.get_x() + bar.get_width() / 2., h * 1.02,
                    label, ha="center", va="bottom",
                    fontsize=12, fontweight="bold")

        # Diff annotation
        diff = ef_val - noef_val
        diff_pct = (diff / noef_val * 100) if noef_val != 0 else 0
        diff_sign = "+" if diff >= 0 else ""

        # Color logic: for some metrics higher is better (Survivors, Delivery),
        # for others lower is better (Latency, Overhead)
        higher_better = name in ("Survivors", "Delivery Prob")
        is_good_for_ef = (diff > 0) if higher_better else (diff < 0)
        diff_color = COLOR_EF if is_good_for_ef else COLOR_NOEF

        ax.text(0.5, -0.18,
                f"Diff: {diff_sign}{diff:.2f}{unit} ({diff_sign}{diff_pct:.1f}%)",
                transform=ax.transAxes, ha="center", fontsize=11,
                color=diff_color, fontweight="bold")

        ax.set_title(name, fontsize=13, fontweight="bold", pad=10)
        ax.grid(True, alpha=0.3, axis="y")
        ax.set_ylim(0, max(ef_val, noef_val) * 1.25 if max(ef_val, noef_val) > 0 else 1)

    fig.suptitle(f"Overall Performance Metrics Comparison  ({CONFIG_LABEL})",
                 fontsize=15, fontweight="bold", y=1.02)
    fig.text(0.5, 0.02,
             "Diff color: GREEN = WithEF better, RED = WithoutEF better",
             ha="center", fontsize=10, style="italic", color="gray")

    plt.tight_layout()
    plt.savefig(output_path, dpi=150, bbox_inches="tight")
    plt.close()
    print(f"  [5/5] Saved: {output_path.name}")


# ============================================================================
# SUMMARY REPORT
# ============================================================================

def print_summary(ef_deaths, noef_deaths, ef_stats, noef_stats):
    """Print summary statistics to console."""
    print("\n" + "=" * 72)
    print("  SUMMARY ANALYSIS — iter05")
    print("=" * 72)
    print(f"\n  Config: {CONFIG_LABEL}")
    print(f"\n  NODE SURVIVAL:")
    print(f"    Total nodes              : {TOTAL_NODES}")
    print(f"    WithEF survivors         : {TOTAL_NODES - len(ef_deaths)} ({(TOTAL_NODES - len(ef_deaths))/TOTAL_NODES*100:.1f}%)")
    print(f"    WithoutEF survivors      : {TOTAL_NODES - len(noef_deaths)} ({(TOTAL_NODES - len(noef_deaths))/TOTAL_NODES*100:.1f}%)")
    print(f"    EF advantage             : +{len(noef_deaths) - len(ef_deaths)} more survivors")
    print(f"    Relative improvement     : {(len(noef_deaths) - len(ef_deaths))/(TOTAL_NODES - len(noef_deaths))*100:.1f}%")

    print(f"\n  CASCADE DYNAMICS:")
    if ef_deaths:
        print(f"    WithEF first death       : {ef_deaths[0]['time']:.0f}s ({ef_deaths[0]['time']/3600:.2f}h)")
        print(f"    WithEF last death        : {ef_deaths[-1]['time']:.0f}s ({ef_deaths[-1]['time']/3600:.2f}h)")
    if noef_deaths:
        print(f"    WithoutEF first death    : {noef_deaths[0]['time']:.0f}s ({noef_deaths[0]['time']/3600:.2f}h)")
        print(f"    WithoutEF last death     : {noef_deaths[-1]['time']:.0f}s ({noef_deaths[-1]['time']/3600:.2f}h)")

    print(f"\n  PERFORMANCE METRICS:")
    print(f"    {'Metric':<25} {'WithEF':>12} {'WithoutEF':>12} {'Diff':>12}")
    print(f"    {'-'*65}")
    for key, label in [("delivery_prob", "Delivery Prob"),
                        ("latency_avg", "Latency Avg (s)"),
                        ("overhead_ratio", "Overhead Ratio"),
                        ("hopcount_avg", "Hopcount Avg"),
                        ("relayed", "Messages Relayed"),
                        ("delivered", "Messages Delivered")]:
        ef_v = ef_stats.get(key, 0)
        noef_v = noef_stats.get(key, 0)
        diff = ef_v - noef_v
        print(f"    {label:<25} {ef_v:>12.4f} {noef_v:>12.4f} {diff:>+12.4f}")

    print(f"\n  NODE TYPE BREAKDOWN:")
    print(f"    {'Type':<20} {'Total':>8} {'WithEF':>10} {'WithoutEF':>12} {'Saved':>8}")
    print(f"    {'-'*60}")
    for type_name, (start, end) in NODE_TYPES.items():
        total = end - start
        ef_died = sum(1 for d in ef_deaths if get_node_type(d["addr"]) == type_name)
        noef_died = sum(1 for d in noef_deaths if get_node_type(d["addr"]) == type_name)
        saved = noef_died - ef_died
        print(f"    {type_name:<20} {total:>8} {ef_died:>10} {noef_died:>12} {saved:>+8}")

    print("\n" + "=" * 72)


# ============================================================================
# MAIN
# ============================================================================

def main():
    # Fix Windows cp1252 console encoding (allow Greek letters & emoji in output)
    import sys
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

    print("=" * 72)
    print("  VISUALISASI iter05 (Best Config from 10-Iteration Sweep)")
    print("=" * 72)

    # Find report files
    ef_node_files = list(WITHEF_DIR.glob("*_NodeDeathReport.txt"))
    noef_node_files = list(WITHOUTEF_DIR.glob("*_NodeDeathReport.txt"))
    ef_msg_files = list(WITHEF_DIR.glob("*_MessageStatsReport.txt"))
    noef_msg_files = list(WITHOUTEF_DIR.glob("*_MessageStatsReport.txt"))

    if not (ef_node_files and noef_node_files and ef_msg_files and noef_msg_files):
        print(f"\nERROR: Cannot find report files in:")
        print(f"  {WITHEF_DIR}")
        print(f"  {WITHOUTEF_DIR}")
        return 1

    print(f"\nParsing reports from {ITER_DIR.name}...")
    ef_deaths = parse_node_deaths(ef_node_files[0])
    noef_deaths = parse_node_deaths(noef_node_files[0])
    ef_stats = parse_message_stats(ef_msg_files[0])
    noef_stats = parse_message_stats(noef_msg_files[0])

    print(f"  WithEF    : {len(ef_deaths)} deaths")
    print(f"  WithoutEF : {len(noef_deaths)} deaths")
    print(f"  Diff      : +{len(noef_deaths) - len(ef_deaths)} more survivors with EF")

    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    print(f"\nGenerating 5 charts to {OUTPUT_DIR}/")

    plot_living_nodes(ef_deaths, noef_deaths,
                      OUTPUT_DIR / "1_living_nodes_over_time.png")
    plot_cumulative_deaths(ef_deaths, noef_deaths,
                            OUTPUT_DIR / "2_cumulative_deaths.png")
    plot_deaths_by_type(ef_deaths, noef_deaths,
                         OUTPUT_DIR / "3_deaths_by_node_type.png")
    plot_death_timeline(ef_deaths, noef_deaths,
                         OUTPUT_DIR / "4_death_timeline_scatter.png")
    plot_performance_metrics(ef_stats, noef_stats, ef_deaths, noef_deaths,
                              OUTPUT_DIR / "5_performance_metrics.png")

    print_summary(ef_deaths, noef_deaths, ef_stats, noef_stats)

    print(f"\n  All 5 charts saved to: {OUTPUT_DIR}")
    print(f"  Buka folder tersebut untuk lihat hasilnya.")
    return 0


if __name__ == "__main__":
    import sys
    sys.exit(main())
