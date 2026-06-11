"""
plot_multirun.py
================
Visualisasi otomatis hasil multiRun Helsinki.

Cara pakai:
    python plot_multirun.py             # plot semua iterasi + summary
    python plot_multirun.py 5           # plot hanya iterasi 5 + summary
    python plot_multirun.py --summary   # hanya buat summary (skip per-iter chart)

Output:
    multiRun/iter_N/chart_iter_N.png   — per-iterasi (survival + stats + scorecard)
    multiRun/summary_chart.png         — ringkasan semua iterasi
"""

import os
import sys
import json
import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
import matplotlib.gridspec as gridspec
from pathlib import Path

# =============================================================================
# CONSTANTS
# =============================================================================

MULTIRUN_DIR = "multiRun"
TOTAL_NODES  = 200      # Helsinki: 64 + 64 + 66 + 2 + 2 + 2
SIM_DURATION = 43200    # 12 jam (endTime config)

COLORS = {
    "with_ef"    : "#2ecc71",   # Hijau
    "without_ef" : "#f39c12",   # Oranye
}
LABELS = {
    "with_ef"    : "With EF (ORQLCI)",
    "without_ef" : "Without EF",
}

try:
    plt.style.use("seaborn-v0_8-whitegrid")
except OSError:
    plt.style.use("seaborn-whitegrid")

# =============================================================================
# PARSERS
# =============================================================================

def parse_node_death(filepath):
    """Baca NodeDeathReport, return DataFrame dengan kolom sim_time dan event."""
    if not os.path.exists(filepath):
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
                    "sim_time" : float(parts[0]),
                    "node_addr": int(parts[1]),
                    "event"    : parts[5],
                })
            except (ValueError, IndexError):
                continue
    if not rows:
        return pd.DataFrame(columns=["sim_time", "node_addr", "event"])
    return pd.DataFrame(rows)


def parse_message_stats(filepath):
    """Baca MessageStatsReport, return dict metric."""
    metrics = {
        "delivery_prob"  : 0.0,
        "overhead_ratio" : 0.0,
        "latency_avg"    : 0.0,
    }
    if not os.path.exists(filepath):
        return metrics
    with open(filepath, "r") as f:
        for line in f:
            line = line.strip()
            if ": " in line:
                key, val = line.split(": ", 1)
                if key in metrics:
                    try:
                        v = float(val)
                        if not np.isnan(v):
                            metrics[key] = v
                    except ValueError:
                        pass
    return metrics


def get_file_paths(iter_num, mode):
    """Return dict path file untuk iter dan mode tertentu."""
    base   = os.path.join(MULTIRUN_DIR, f"iter_{iter_num}", mode)
    prefix = f"{mode}_iter_{iter_num}"
    return {
        "death" : os.path.join(base, f"{prefix}_NodeDeathReport.txt"),
        "stats" : os.path.join(base, f"{prefix}_MessageStatsReport.txt"),
        "params": os.path.join(base, "params.json"),
    }


def count_alive(df):
    """Hitung node yang masih hidup di akhir sim."""
    if df is None or df.empty:
        return TOTAL_NODES
    dead = len(df[df["event"] == "DEAD"])
    return TOTAL_NODES - dead


def load_iter_data(iter_num):
    """Load semua data untuk satu iterasi, return dict."""
    paths_ef  = get_file_paths(iter_num, "with_ef")
    paths_nef = get_file_paths(iter_num, "without_ef")

    df_ef   = parse_node_death(paths_ef["death"])
    df_nef  = parse_node_death(paths_nef["death"])
    stats_ef  = parse_message_stats(paths_ef["stats"])
    stats_nef = parse_message_stats(paths_nef["stats"])

    params = {}
    if os.path.exists(paths_ef["params"]):
        with open(paths_ef["params"]) as f:
            params = json.load(f)

    return {
        "iter"      : iter_num,
        "df_ef"     : df_ef,
        "df_nef"    : df_nef,
        "stats_ef"  : stats_ef,
        "stats_nef" : stats_nef,
        "params"    : params,
        "alive_ef"  : count_alive(df_ef),
        "alive_nef" : count_alive(df_nef),
        "del_ef"    : stats_ef["delivery_prob"],
        "del_nef"   : stats_nef["delivery_prob"],
        "ovr_ef"    : stats_ef["overhead_ratio"],
        "ovr_nef"   : stats_nef["overhead_ratio"],
        "lat_ef"    : stats_ef["latency_avg"],
        "lat_nef"   : stats_nef["latency_avg"],
    }


def fmt_time(x, _):
    h = int(x // 3600)
    m = int((x % 3600) // 60)
    return f"{h}h{m:02d}m"


def discover_iterations():
    """Cari iterasi yang sudah selesai (minimal satu mode ada MessageStats)."""
    if not os.path.exists(MULTIRUN_DIR):
        return []
    iters = []
    for entry in sorted(os.listdir(MULTIRUN_DIR)):
        if not entry.startswith("iter_"):
            continue
        try:
            n = int(entry.split("_")[1])
        except (IndexError, ValueError):
            continue
        has_with    = os.path.exists(get_file_paths(n, "with_ef")["stats"])
        has_without = os.path.exists(get_file_paths(n, "without_ef")["stats"])
        if has_with or has_without:
            iters.append(n)
    return sorted(iters)


# =============================================================================
# PER-ITERATION CHART
# =============================================================================

def plot_iter(data):
    """Buat chart per-iterasi: survival curve + total dead + 3 metric + scorecard."""
    iter_num = data["iter"]
    params   = data["params"]

    fig = plt.figure(figsize=(20, 10))
    fig.patch.set_facecolor("white")

    # Judul dengan info params
    param_str = ""
    if params:
        param_str = (
            f"  |  init={int(params.get('initialEnergy', 0))}  "
            f"tx={params.get('transmitEnergy', 0)}  "
            f"rx={params.get('receiveEnergy', 0)}  "
            f"scan={params.get('scanEnergy', 0)}  "
            f"(est. high_death={params.get('_high_death_hours', 0):.1f}h  "
            f"low_death={params.get('_low_death_hours', 0):.1f}h)"
        )
    fig.suptitle(
        f"Iterasi {iter_num} — Helsinki MultiRun{param_str}",
        fontsize=12, fontweight="bold", y=0.99
    )

    gs = gridspec.GridSpec(2, 4, figure=fig, hspace=0.45, wspace=0.38)
    ax_surv = fig.add_subplot(gs[0, :3])
    ax_dead = fig.add_subplot(gs[0, 3])
    ax_del  = fig.add_subplot(gs[1, 0])
    ax_ovr  = fig.add_subplot(gs[1, 1])
    ax_lat  = fig.add_subplot(gs[1, 2])
    ax_card = fig.add_subplot(gs[1, 3])

    labels_short = ["With EF", "Without EF"]
    colors_bar   = [COLORS["with_ef"], COLORS["without_ef"]]

    # ── Survival Curve ────────────────────────────────────────────────────────
    ax_surv.set_title("Grafik Sisa Node Aktif", fontsize=12, fontweight="bold")
    ax_surv.set_xlabel("Waktu Simulasi", fontsize=11)
    ax_surv.set_ylabel("Jumlah Node Aktif", fontsize=11)
    ax_surv.set_xlim(0, SIM_DURATION)
    ax_surv.set_ylim(0, TOTAL_NODES + 20)
    ax_surv.xaxis.set_major_formatter(plt.FuncFormatter(fmt_time))
    ax_surv.axhline(TOTAL_NODES, color="gray", linewidth=1, linestyle="--", alpha=0.4)

    for mode, df, color, label in [
        ("with_ef",    data["df_ef"],  COLORS["with_ef"],    LABELS["with_ef"]),
        ("without_ef", data["df_nef"], COLORS["without_ef"], LABELS["without_ef"]),
    ]:
        if df is None or df.empty:
            times = np.array([0, SIM_DURATION])
            alive = np.array([TOTAL_NODES, TOTAL_NODES])
        else:
            df_dead = df[df["event"] == "DEAD"]
            if df_dead.empty:
                times = np.array([0, SIM_DURATION])
                alive = np.array([TOTAL_NODES, TOTAL_NODES])
            else:
                sorted_t = np.sort(df_dead["sim_time"].values)
                times    = np.concatenate([[0], sorted_t, [SIM_DURATION]])
                deaths   = np.arange(len(sorted_t) + 1)
                alive    = TOTAL_NODES - np.concatenate([[0], deaths])

        final = int(alive[-1])
        ax_surv.step(times, alive, where="post", color=color, linewidth=2.5, label=label)
        ax_surv.text(
            SIM_DURATION * 0.985, final + 2.5,
            f"{final} aktif",
            color=color, fontweight="bold", ha="right", va="bottom", fontsize=10
        )

    ax_surv.legend(loc="lower left", fontsize=10, frameon=True, shadow=True)

    # ── Total Dead Bar ────────────────────────────────────────────────────────
    ax_dead.set_title("Total Node Mati", fontsize=12, fontweight="bold")
    dead_counts = [
        TOTAL_NODES - data["alive_ef"],
        TOTAL_NODES - data["alive_nef"],
    ]
    bars = ax_dead.bar(labels_short, dead_counts, color=colors_bar, edgecolor="black", width=0.5, alpha=0.9)
    for bar, cnt in zip(bars, dead_counts):
        ax_dead.text(
            bar.get_x() + bar.get_width() / 2,
            bar.get_height() + 1.5,
            str(cnt), ha="center", va="bottom", fontweight="bold", fontsize=13
        )
    ax_dead.set_ylim(0, TOTAL_NODES + 25)
    ax_dead.axhline(TOTAL_NODES, color="gray", linewidth=1, linestyle="--", alpha=0.4)
    ax_dead.text(1.5, TOTAL_NODES + 2, f"Maks: {TOTAL_NODES}", color="gray", fontsize=9, ha="right")
    ax_dead.tick_params(axis="x", rotation=10)

    # ── Message Stats Bars ────────────────────────────────────────────────────
    stat_configs = [
        (ax_del, "Delivery Ratio",  "del_ef",  "del_nef",  ".4f", 0.0, 1.05),
        (ax_ovr, "Overhead Ratio",  "ovr_ef",  "ovr_nef",  ".1f", None, None),
        (ax_lat, "Avg Latency (s)", "lat_ef",  "lat_nef",  ".0f", None, None),
    ]
    for ax, title, key_ef, key_nef, fmt, ymin, ymax in stat_configs:
        vals   = [data[key_ef], data[key_nef]]
        bars_s = ax.bar(labels_short, vals, color=colors_bar, edgecolor="black", width=0.5, alpha=0.9)
        ax.set_title(title, fontsize=11, fontweight="bold")
        ax.grid(axis="y", linestyle="--", alpha=0.6)
        ax.tick_params(axis="x", rotation=10)
        if ymin is not None:
            ax.set_ylim(ymin, ymax)
        mv = max(vals) if max(vals) > 0 else 1
        for bar, v in zip(bars_s, vals):
            ax.text(
                bar.get_x() + bar.get_width() / 2,
                bar.get_height() + mv * 0.012,
                f"{v:{fmt}}", ha="center", va="bottom", fontsize=10
            )

    # ── Scorecard ─────────────────────────────────────────────────────────────
    ax_card.axis("off")
    ax_card.set_title("Scorecard", fontsize=12, fontweight="bold")

    def win_symbol(ve, vn, higher=True):
        if higher:
            if ve > vn:   return ("[WIN] EF Menang", "#27ae60")
            elif ve < vn: return ("[LOSS] EF Kalah",  "#e74c3c")
            else:         return ("[DRAW] Seri",       "#7f8c8d")
        else:
            if ve < vn:   return ("[WIN] EF Menang", "#27ae60")
            elif ve > vn: return ("[LOSS] EF Kalah",  "#e74c3c")
            else:         return ("[DRAW] Seri",       "#7f8c8d")

    scorecard = [
        ("Survivor",
         f"{data['alive_ef']} vs {data['alive_nef']} node aktif",
         win_symbol(data["alive_ef"],  data["alive_nef"],  higher=True)),
        ("Delivery",
         f"{data['del_ef']:.4f} vs {data['del_nef']:.4f}",
         win_symbol(data["del_ef"],    data["del_nef"],    higher=True)),
        ("Overhead",
         f"{data['ovr_ef']:.2f} vs {data['ovr_nef']:.2f}",
         win_symbol(data["ovr_ef"],    data["ovr_nef"],    higher=False)),
        ("Latency",
         f"{data['lat_ef']:.0f}s vs {data['lat_nef']:.0f}s",
         win_symbol(data["lat_ef"],    data["lat_nef"],    higher=False)),
    ]

    y = 0.88
    for metric, values, (result_text, result_color) in scorecard:
        ax_card.text(0.08, y,        metric,       transform=ax_card.transAxes,
                     fontsize=10, fontweight="bold", color="#2c3e50")
        ax_card.text(0.08, y - 0.10, values,       transform=ax_card.transAxes,
                     fontsize=9,  color="#555555")
        ax_card.text(0.08, y - 0.20, result_text,  transform=ax_card.transAxes,
                     fontsize=10, fontweight="bold", color=result_color)
        y -= 0.30

    # Simpan
    out_dir  = os.path.join(MULTIRUN_DIR, f"iter_{iter_num}")
    out_path = os.path.join(out_dir, f"chart_iter_{iter_num}.png")
    os.makedirs(out_dir, exist_ok=True)
    plt.savefig(out_path, dpi=150, bbox_inches="tight", facecolor="white")
    plt.close()
    print(f"  [OK] iter_{iter_num}/chart_iter_{iter_num}.png")


# =============================================================================
# SUMMARY CHART
# =============================================================================

def plot_summary(all_data):
    """Summary: grouped bar 4 metrik + win-rate tally di bawah."""
    if not all_data:
        print("Tidak ada data untuk summary.")
        return

    n        = len(all_data)
    iters    = [d["iter"] for d in all_data]
    x        = np.arange(n)
    bar_w    = 0.35
    xlabels  = [f"Iter {i}" for i in iters]

    fig, axes = plt.subplots(2, 2, figsize=(22, 13))
    fig.patch.set_facecolor("white")
    fig.suptitle(
        "Summary MultiRun Helsinki — With EF vs Without EF",
        fontsize=17, fontweight="bold", y=0.99
    )

    plot_specs = [
        (axes[0, 0], "Jumlah Node Survivor di Akhir Sim",
         "alive_ef", "alive_nef", True,  0, TOTAL_NODES + 25, ".0f"),
        (axes[0, 1], "Delivery Ratio (delivery_prob)",
         "del_ef",   "del_nef",   True,  0, 1.05,             ".4f"),
        (axes[1, 0], "Overhead Ratio",
         "ovr_ef",   "ovr_nef",   False, None, None,           ".1f"),
        (axes[1, 1], "Average Latency (s)",
         "lat_ef",   "lat_nef",   False, None, None,           ".0f"),
    ]

    win_counts = {"survivor": 0, "delivery": 0, "overhead": 0, "latency": 0}
    win_keys   = list(win_counts.keys())

    for ax_idx, (ax, title, key_ef, key_nef, higher, ymin, ymax, fmt) in enumerate(plot_specs):
        vals_ef  = [d[key_ef]  for d in all_data]
        vals_nef = [d[key_nef] for d in all_data]

        b1 = ax.bar(x - bar_w / 2, vals_ef,  bar_w,
                    label=LABELS["with_ef"],    color=COLORS["with_ef"],
                    edgecolor="black", alpha=0.88)
        b2 = ax.bar(x + bar_w / 2, vals_nef, bar_w,
                    label=LABELS["without_ef"], color=COLORS["without_ef"],
                    edgecolor="black", alpha=0.88)

        ax.set_title(title, fontsize=13, fontweight="bold")
        ax.set_xticks(x)
        ax.set_xticklabels(xlabels, rotation=30, ha="right", fontsize=9)
        ax.legend(fontsize=10)
        ax.grid(axis="y", linestyle="--", alpha=0.6)
        if ymin is not None:
            ax.set_ylim(ymin, ymax)

        # Highlight background iterasi yang EF menang
        wk = win_keys[ax_idx]
        for i, (ve, vn) in enumerate(zip(vals_ef, vals_nef)):
            ef_wins = (ve > vn) if higher else (ve < vn)
            if ef_wins:
                win_counts[wk] += 1
                ax.axvspan(i - 0.5, i + 0.5, alpha=0.10, color=COLORS["with_ef"], zorder=0)

        # Label di atas bar untuk semua metrik
        mv = max(vals_ef + vals_nef) if max(vals_ef + vals_nef) > 0 else 1
        for bar, v in zip(b1, vals_ef):
            ax.text(bar.get_x() + bar.get_width() / 2,
                    bar.get_height() + mv * 0.008,
                    f"{v:{fmt}}", ha="center", va="bottom",
                    fontsize=7.5, color=COLORS["with_ef"], fontweight="bold")
        for bar, v in zip(b2, vals_nef):
            ax.text(bar.get_x() + bar.get_width() / 2,
                    bar.get_height() + mv * 0.008,
                    f"{v:{fmt}}", ha="center", va="bottom",
                    fontsize=7.5, color=COLORS["without_ef"], fontweight="bold")

    # Win-rate tally
    tally = (
        f"  Win Rate EF dari {n} iterasi:\n"
        f"  Survivor : {win_counts['survivor']}/{n}   "
        f"Delivery : {win_counts['delivery']}/{n}   "
        f"Overhead : {win_counts['overhead']}/{n}   "
        f"Latency  : {win_counts['latency']}/{n}"
    )
    fig.text(
        0.5, 0.005, tally, ha="center", fontsize=11, family="monospace",
        verticalalignment="bottom",
        bbox=dict(boxstyle="round,pad=0.4", facecolor="#ecf0f1", alpha=0.85)
    )

    plt.tight_layout(rect=[0, 0.05, 1, 0.97])

    out_path = os.path.join(MULTIRUN_DIR, "summary_chart.png")
    plt.savefig(out_path, dpi=150, bbox_inches="tight", facecolor="white")
    plt.close()
    print(f"\n[OK] summary_chart.png → {os.path.abspath(out_path)}")
    print(f"     Win rate EF: survivor={win_counts['survivor']}/{n}  "
          f"delivery={win_counts['delivery']}/{n}  "
          f"overhead={win_counts['overhead']}/{n}  "
          f"latency={win_counts['latency']}/{n}")


# =============================================================================
# MAIN
# =============================================================================

def main():
    args         = sys.argv[1:]
    only_summary = "--summary" in args
    specific     = [int(a) for a in args if a.isdigit()]

    available = discover_iterations()
    if not available:
        print("Tidak ada iterasi yang selesai di folder multiRun/")
        sys.exit(0)

    targets = specific if specific else available

    print("=== plot_multirun.py ===")
    print(f"Iterasi tersedia : {available}")
    print(f"Iterasi diplot   : {targets}")
    print(f"Only summary     : {only_summary}\n")

    all_data = []
    for it in targets:
        if it not in available:
            print(f"  [SKIP] Iterasi {it} belum ada hasilnya")
            continue
        data = load_iter_data(it)
        if not only_summary:
            plot_iter(data)
        all_data.append(data)

    print("\nMembuat summary chart...")
    plot_summary(all_data)
    print("\nSelesai!")



if __name__ == "__main__":
    main()
