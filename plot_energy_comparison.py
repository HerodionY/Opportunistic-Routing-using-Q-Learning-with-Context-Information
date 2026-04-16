#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
=============================================================================
ORQLCI Energy-Aware Simulation: Visualisasi 4 Metrik Perbandingan
=============================================================================
Membaca data dari report files di:
  - reports/energyAware/ORLCI/
  - reports/energyAware/Prophet/
  - reports/energyAware/Epidemic/

4 Metrik:
  1. Delivery Ratio
  2. Overhead Ratio
  3. Average Latency
  4. Dead Nodes over Time (Energy Depletion)

Output: 
  - reports/energyAware/comparison_metrics.png (4 bar charts)
  - reports/energyAware/dead_nodes_timeline.png (line chart)
"""

import matplotlib.pyplot as plt
import matplotlib
import numpy as np
import os
import re
import sys

# ─── Configuration ───────────────────────────────────────────────────────────

BASE_DIR = os.path.dirname(os.path.abspath(__file__))

# Protocol configurations: name, report directory, scenario name prefix
PROTOCOLS = {
    'ORQLCI': {
        'dir': os.path.join(BASE_DIR, 'reports', 'energyAware', 'ORLCI'),
        'prefix': 'Bench-CCRouting-EnergyAware',
        'color': '#2196F3',    # Blue
        'marker': 'o',
    },
    'PRoPHET': {
        'dir': os.path.join(BASE_DIR, 'reports', 'energyAware', 'Prophet'),
        'prefix': 'Bench-Prophet-EnergyAware',
        'color': '#FF9800',    # Orange
        'marker': 's',
    },
    'Epidemic': {
        'dir': os.path.join(BASE_DIR, 'reports', 'energyAware', 'Epidemic'),
        'prefix': 'Bench-Epidemic-EnergyAware',
        'color': '#4CAF50',    # Green
        'marker': '^',
    },
}

OUTPUT_DIR = os.path.join(BASE_DIR, 'reports', 'energyAware')

# ─── Parsing Functions ───────────────────────────────────────────────────────

def parse_message_stats(filepath):
    """Parse MessageStatsReport file."""
    metrics = {
        'delivery_prob': 0.0,
        'overhead_ratio': 0.0,
        'latency_avg': 0.0,
        'dropped': 0,
        'created': 0,
        'delivered': 0,
        'relayed': 0,
        'hopcount_avg': 0.0,
    }
    if not os.path.exists(filepath):
        print(f"  [WARN] File tidak ditemukan: {filepath}")
        return metrics

    with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
        for line in f:
            line = line.strip()
            if ': ' in line:
                key, val = line.split(': ', 1)
                key = key.strip()
                val = val.strip()
                if key in metrics:
                    try:
                        if key in ('dropped', 'created', 'delivered', 'relayed'):
                            metrics[key] = int(val)
                        else:
                            metrics[key] = float(val) if val != 'NaN' else 0.0
                    except ValueError:
                        pass
    return metrics


def parse_performance_report(filepath):
    """Parse PerformanceReport file."""
    metrics = {
        'delivery_ratio': 0.0,
        'overhead_ratio': 0.0,
        'average_latency': 0.0,
        'router': 'Unknown',
        'created': 0,
        'relayed': 0,
        'delivered': 0,
    }
    if not os.path.exists(filepath):
        print(f"  [WARN] File tidak ditemukan: {filepath}")
        return metrics

    with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
        for line in f:
            line = line.strip()
            if line.startswith('Delivery Ratio:'):
                metrics['delivery_ratio'] = float(line.split(':')[1].strip())
            elif line.startswith('Overhead Ratio:'):
                val = line.split(':')[1].strip()
                metrics['overhead_ratio'] = float(val) if val != 'NaN' else 0.0
            elif line.startswith('Average Latency:'):
                val = line.split(':')[1].strip()
                metrics['average_latency'] = float(val) if val != 'NaN' else 0.0
            elif line.startswith('Router used:'):
                metrics['router'] = line.split(':')[1].strip()
            elif line.startswith('Created Messages:'):
                metrics['created'] = int(line.split(':')[1].strip())
            elif line.startswith('Relayed Messages:'):
                metrics['relayed'] = int(line.split(':')[1].strip())
            elif line.startswith('Delivered Messages:'):
                metrics['delivered'] = int(line.split(':')[1].strip())
    return metrics


def parse_dead_nodes_new_format(filepath):
    """
    Parse DeadNodesReport (format baru):
    # time dead_nodes alive_nodes tracked_nodes dead_ratio_pct
    300 0 126 126 0.0000
    """
    times = []
    dead_counts = []
    dead_ratios = []
    
    if not os.path.exists(filepath):
        print(f"  [WARN] File tidak ditemukan: {filepath}")
        return times, dead_counts, dead_ratios

    with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            parts = line.split()
            if len(parts) >= 5:
                try:
                    t = int(parts[0])
                    dead = int(parts[1])
                    ratio = float(parts[4])
                    times.append(t)
                    dead_counts.append(dead)
                    dead_ratios.append(ratio)
                except ValueError:
                    continue
    return times, dead_counts, dead_ratios


def parse_dead_nodes_old_format(filepath):
    """
    Parse DeadNodesReport (format lama):
    [100] dead=0/126 ratio=0.0000
    """
    times = []
    dead_counts = []
    dead_ratios = []
    
    if not os.path.exists(filepath):
        print(f"  [WARN] File tidak ditemukan: {filepath}")
        return times, dead_counts, dead_ratios

    pattern = re.compile(r'\[(\d+)\]\s+dead=(\d+)/(\d+)\s+ratio=(\d+\.\d+)')
    with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
        for line in f:
            m = pattern.match(line.strip())
            if m:
                t = int(m.group(1))
                dead = int(m.group(2))
                ratio = float(m.group(4)) * 100  # Convert to percentage
                times.append(t)
                dead_counts.append(dead)
                dead_ratios.append(ratio)
    return times, dead_counts, dead_ratios


def parse_dead_nodes(filepath):
    """Auto-detect and parse DeadNodesReport (supports both formats)."""
    if not os.path.exists(filepath):
        print(f"  [WARN] File tidak ditemukan: {filepath}")
        return [], [], []
    
    with open(filepath, 'r', encoding='utf-8', errors='ignore') as f:
        first_data_line = ''
        for line in f:
            line = line.strip()
            if line and not line.startswith('#'):
                first_data_line = line
                break
    
    if first_data_line.startswith('['):
        return parse_dead_nodes_old_format(filepath)
    else:
        return parse_dead_nodes_new_format(filepath)


# ─── Plotting Functions ──────────────────────────────────────────────────────

def setup_matplotlib():
    """Set up professional matplotlib styling."""
    plt.rcParams.update({
        'font.family': 'sans-serif',
        'font.sans-serif': ['Segoe UI', 'Arial', 'Helvetica', 'DejaVu Sans'],
        'font.size': 11,
        'axes.titlesize': 13,
        'axes.labelsize': 11,
        'xtick.labelsize': 10,
        'ytick.labelsize': 10,
        'legend.fontsize': 10,
        'figure.dpi': 150,
        'savefig.dpi': 300,
        'savefig.bbox': 'tight',
        'axes.grid': True,
        'grid.alpha': 0.3,
        'axes.spines.top': False,
        'axes.spines.right': False,
    })


def plot_bar_comparison(protocols, data, output_path):
    """Generate 4-panel bar comparison chart."""
    fig, axs = plt.subplots(2, 2, figsize=(14, 10))
    fig.suptitle('Perbandingan Kinerja Routing Protocol (Energy-Aware)',
                 fontsize=16, fontweight='bold', y=0.98)
    
    names = list(protocols.keys())
    colors = [protocols[n]['color'] for n in names]
    x = np.arange(len(names))
    bar_width = 0.5

    # ── 1. Delivery Ratio ──
    ax = axs[0, 0]
    vals = [data[n]['delivery_ratio'] for n in names]
    bars = ax.bar(x, vals, bar_width, color=colors, edgecolor='white', linewidth=1.2)
    ax.set_title('Delivery Ratio', fontweight='bold')
    ax.set_ylabel('Rasio (delivered / created)')
    ax.set_xticks(x)
    ax.set_xticklabels(names)
    for i, (bar, v) in enumerate(zip(bars, vals)):
        ax.text(bar.get_x() + bar.get_width()/2, bar.get_height() + max(vals)*0.02,
                f'{v:.4f}', ha='center', va='bottom', fontweight='bold', fontsize=10)

    # ── 2. Overhead Ratio ──
    ax = axs[0, 1]
    vals = [data[n]['overhead_ratio'] for n in names]
    bars = ax.bar(x, vals, bar_width, color=colors, edgecolor='white', linewidth=1.2)
    ax.set_title('Overhead Ratio', fontweight='bold')
    ax.set_ylabel('Rasio (relayed-delivered) / delivered')
    ax.set_xticks(x)
    ax.set_xticklabels(names)
    for i, (bar, v) in enumerate(zip(bars, vals)):
        ax.text(bar.get_x() + bar.get_width()/2, bar.get_height() + max(vals)*0.02,
                f'{v:.2f}', ha='center', va='bottom', fontweight='bold', fontsize=10)

    # ── 3. Average Latency ──
    ax = axs[1, 0]
    vals = [data[n]['average_latency'] for n in names]
    bars = ax.bar(x, vals, bar_width, color=colors, edgecolor='white', linewidth=1.2)
    ax.set_title('Average Latency', fontweight='bold')
    ax.set_ylabel('Waktu (detik)')
    ax.set_xticks(x)
    ax.set_xticklabels(names)
    for i, (bar, v) in enumerate(zip(bars, vals)):
        ax.text(bar.get_x() + bar.get_width()/2, bar.get_height() + max(vals)*0.02,
                f'{v:.1f}s', ha='center', va='bottom', fontweight='bold', fontsize=10)

    # ── 4. Dropped Messages ──
    ax = axs[1, 1]
    vals = [data[n]['dropped'] for n in names]
    bars = ax.bar(x, vals, bar_width, color=colors, edgecolor='white', linewidth=1.2)
    ax.set_title('Dropped Messages', fontweight='bold')
    ax.set_ylabel('Jumlah Pesan')
    ax.set_xticks(x)
    ax.set_xticklabels(names)
    for i, (bar, v) in enumerate(zip(bars, vals)):
        ax.text(bar.get_x() + bar.get_width()/2, bar.get_height() + max(vals)*0.02,
                f'{int(v)}', ha='center', va='bottom', fontweight='bold', fontsize=10)

    plt.tight_layout(rect=[0, 0.02, 1, 0.95])
    plt.savefig(output_path)
    print(f"\n[OK] Grafik metrik disimpan: {output_path}")
    plt.close()


def plot_dead_nodes_timeline(protocols, dead_data, output_path):
    """Generate dead nodes timeline comparison chart with zoomed view."""
    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(16, 7))
    fig.suptitle('Perbandingan Energy Depletion (Dead Nodes over Time)',
                 fontsize=16, fontweight='bold', y=0.98)

    # Find the time range where nodes start dying (zoom region)
    first_death_time = float('inf')
    max_time = 0
    for name in protocols:
        times, dead_counts, dead_ratios = dead_data[name]
        if not times:
            continue
        max_time = max(max_time, max(times))
        for i, dc in enumerate(dead_counts):
            if dc > 0:
                first_death_time = min(first_death_time, times[i])
                break

    # Zoom start: a bit before first death to show context
    zoom_start = max(0, first_death_time - 1500)  # 1500s before first death

    for name in protocols:
        cfg = protocols[name]
        times, dead_counts, dead_ratios = dead_data[name]
        if not times:
            continue
        
        # Convert time to minutes for the zoomed view
        times_min = [t / 60.0 for t in times]
        
        # LEFT: Full timeline (step plot with fill)
        ax1.step(times_min, dead_counts, label=name, color=cfg['color'],
                 linewidth=2.5, alpha=0.9, where='post')
        ax1.fill_between(times_min, dead_counts, step='post',
                         color=cfg['color'], alpha=0.15)

        # RIGHT: Zoomed view (only data from zoom_start onwards)
        zoom_times = []
        zoom_counts = []
        for t, dc in zip(times, dead_counts):
            if t >= zoom_start:
                zoom_times.append(t / 60.0)
                zoom_counts.append(dc)
        
        ax2.step(zoom_times, zoom_counts, label=name, color=cfg['color'],
                 linewidth=2.5, alpha=0.9, where='post',
                 marker=cfg['marker'], markersize=6)
        ax2.fill_between(zoom_times, zoom_counts, step='post',
                         color=cfg['color'], alpha=0.15)
        
        # Annotate final value on zoomed chart
        if zoom_counts:
            ax2.annotate(f'{zoom_counts[-1]} nodes',
                        xy=(zoom_times[-1], zoom_counts[-1]),
                        xytext=(-40, 10), textcoords='offset points',
                        fontsize=9, fontweight='bold', color=cfg['color'],
                        arrowprops=dict(arrowstyle='->', color=cfg['color'], lw=1.5))

    # LEFT: Full timeline
    ax1.set_title('Full Timeline - Dead Nodes Count', fontweight='bold')
    ax1.set_xlabel('Waktu Simulasi (menit)')
    ax1.set_ylabel('Jumlah Node Mati')
    ax1.legend(loc='upper left', framealpha=0.9)
    ax1.set_ylim(bottom=0)
    ax1.set_xlim(left=0)
    # Add shaded zone indicating critical period
    ax1.axvspan(zoom_start/60.0, max_time/60.0, alpha=0.08, color='red',
                label='_nolegend_')
    ax1.annotate('Zona Kritis', xy=(zoom_start/60.0, ax1.get_ylim()[1]*0.5),
                fontsize=9, fontstyle='italic', color='red', alpha=0.7)

    # RIGHT: Zoomed critical period
    ax2.set_title('Zoom: Periode Kritis (Node Mulai Mati)', fontweight='bold')
    ax2.set_xlabel('Waktu Simulasi (menit)')
    ax2.set_ylabel('Jumlah Node Mati')
    ax2.legend(loc='upper left', framealpha=0.9)
    ax2.set_ylim(bottom=0)
    ax2.set_xlim(left=zoom_start/60.0)

    plt.tight_layout(rect=[0, 0.02, 1, 0.93])
    plt.savefig(output_path)
    print(f"[OK] Grafik dead nodes disimpan: {output_path}")
    plt.close()


def print_summary_table(protocols, data, dead_data):
    """Print summary results table to console."""
    print("\n" + "="*80)
    print("RINGKASAN HASIL SIMULASI ENERGY-AWARE")
    print("="*80)
    
    header = f"{'Metrik':<25}"
    for name in protocols:
        header += f" | {name:>15}"
    print(header)
    print("-" * 80)

    # Delivery Ratio
    row = f"{'Delivery Ratio':<25}"
    for name in protocols:
        row += f" | {data[name]['delivery_ratio']:>15.4f}"
    print(row)

    # Overhead Ratio
    row = f"{'Overhead Ratio':<25}"
    for name in protocols:
        row += f" | {data[name]['overhead_ratio']:>15.4f}"
    print(row)

    # Average Latency
    row = f"{'Average Latency (s)':<25}"
    for name in protocols:
        row += f" | {data[name]['average_latency']:>15.2f}"
    print(row)

    # Dropped Messages
    row = f"{'Dropped Messages':<25}"
    for name in protocols:
        row += f" | {data[name]['dropped']:>15d}"
    print(row)

    # Created Messages
    row = f"{'Created Messages':<25}"
    for name in protocols:
        row += f" | {data[name]['created']:>15d}"
    print(row)

    # Delivered Messages
    row = f"{'Delivered Messages':<25}"
    for name in protocols:
        row += f" | {data[name]['delivered']:>15d}"
    print(row)

    # Relayed Messages
    row = f"{'Relayed Messages':<25}"
    for name in protocols:
        row += f" | {data[name]['relayed']:>15d}"
    print(row)

    # Dead Nodes (akhir simulasi)
    row = f"{'Dead Nodes (akhir)':<25}"
    for name in protocols:
        times, counts, ratios = dead_data[name]
        if counts:
            row += f" | {counts[-1]:>10d} ({ratios[-1]:.1f}%)"
        else:
            row += f" | {'N/A':>15}"
    print(row)
    
    print("="*80)


# ─── Main ─────────────────────────────────────────────────────────────────────

def main():
    print("="*60)
    print("  ORQLCI Energy-Aware: Plot 4 Metrik Perbandingan")
    print("="*60)

    setup_matplotlib()

    all_data = {}
    all_dead = {}

    for name, cfg in PROTOCOLS.items():
        print(f"\n[DIR] Membaca data untuk: {name}")
        report_dir = cfg['dir']
        prefix = cfg['prefix']
        
        # Read MessageStatsReport
        msg_file = os.path.join(report_dir, f"{prefix}_MessageStatsReport.txt")
        msg_stats = parse_message_stats(msg_file)
        print(f"   [STATS] MessageStats: delivered={msg_stats['delivered']}, "
              f"created={msg_stats['created']}, dropped={msg_stats['dropped']}")
        
        # Read PerformanceReport
        perf_file = os.path.join(report_dir, f"{prefix}_PerformanceReport.txt")
        perf_stats = parse_performance_report(perf_file)
        print(f"   [PERF] Performance: DR={perf_stats['delivery_ratio']:.4f}, "
              f"OR={perf_stats['overhead_ratio']:.4f}, "
              f"Latency={perf_stats['average_latency']:.2f}s")
        
        # Read DeadNodesReport
        dead_file = os.path.join(report_dir, f"{prefix}_DeadNodesReport.txt")
        times, dead_counts, dead_ratios = parse_dead_nodes(dead_file)
        if times:
            print(f"   [DEAD] DeadNodes: {len(times)} data points, "
                  f"final={dead_counts[-1]} dead ({dead_ratios[-1]:.1f}%)")
        
        # Combine data
        all_data[name] = {
            'delivery_ratio': perf_stats['delivery_ratio'],
            'overhead_ratio': perf_stats['overhead_ratio'],
            'average_latency': perf_stats['average_latency'],
            'dropped': msg_stats['dropped'],
            'created': msg_stats['created'],
            'delivered': msg_stats['delivered'],
            'relayed': msg_stats['relayed'],
        }
        all_dead[name] = (times, dead_counts, dead_ratios)

    # Print summary table
    print_summary_table(PROTOCOLS, all_data, all_dead)

    # Generate plots
    os.makedirs(OUTPUT_DIR, exist_ok=True)
    
    metrics_path = os.path.join(OUTPUT_DIR, 'comparison_metrics.png')
    plot_bar_comparison(PROTOCOLS, all_data, metrics_path)
    
    timeline_path = os.path.join(OUTPUT_DIR, 'dead_nodes_timeline.png')
    plot_dead_nodes_timeline(PROTOCOLS, all_dead, timeline_path)

    print(f"\n[OK] Semua grafik selesai di-generate di: {OUTPUT_DIR}")
    print("   - comparison_metrics.png")
    print("   - dead_nodes_timeline.png")


if __name__ == '__main__':
    main()
