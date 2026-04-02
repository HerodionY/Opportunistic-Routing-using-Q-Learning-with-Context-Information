import argparse
import os
import re
from datetime import datetime


def parse_config(path):
    data = {}
    if not os.path.exists(path):
        raise FileNotFoundError(f"Config not found: {path}")
    with open(path, "r") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            if "=" not in line:
                continue
            key, val = line.split("=", 1)
            data[key.strip()] = val.strip()
    return data


def parse_message_stats(path):
    metrics = {
        "delivery_prob": None,
        "overhead_ratio": None,
        "latency_avg": None,
        "dropped": None,
    }
    if not os.path.exists(path):
        return metrics
    with open(path, "r") as f:
        for line in f:
            line = line.strip()
            if ": " not in line:
                continue
            key, val = line.split(": ", 1)
            if key in metrics:
                try:
                    metrics[key] = float(val)
                except ValueError:
                    metrics[key] = None
    return metrics


def parse_performance_report(path):
    metrics = {
        "perf_delivery_ratio": None,
        "perf_overhead_ratio": None,
        "perf_latency_avg": None,
    }
    if not os.path.exists(path):
        return metrics
    with open(path, "r") as f:
        for line in f:
            line = line.strip()
            if line.startswith("Delivery Ratio:"):
                metrics["perf_delivery_ratio"] = safe_float(line.split(":")[1].strip())
            elif line.startswith("Overhead Ratio:"):
                metrics["perf_overhead_ratio"] = safe_float(line.split(":")[1].strip())
            elif line.startswith("Average Latency:"):
                metrics["perf_latency_avg"] = safe_float(line.split(":")[1].strip())
    return metrics


def parse_dead_nodes(path):
    metrics = {
        "dead_final": None,
        "dead_ratio_final": None,
        "dead_total": None,
    }
    if not os.path.exists(path):
        return metrics
    last = None
    pattern = re.compile(r"dead=(\d+)/(\d+)\s+ratio=([0-9.]+)")
    with open(path, "r") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            m = pattern.search(line)
            if m:
                last = m
    if last:
        dead = int(last.group(1))
        total = int(last.group(2))
        ratio = safe_float(last.group(3))
        metrics["dead_final"] = dead
        metrics["dead_ratio_final"] = ratio
        metrics["dead_total"] = total
    return metrics


def safe_float(s):
    try:
        return float(s)
    except ValueError:
        return None


def make_report_paths(report_dir, scenario_name):
    base = os.path.join(report_dir, scenario_name)
    return {
        "message_stats": base + "_MessageStatsReport.txt",
        "performance": base + "_PerformanceReport.txt",
        "dead_nodes": base + "_DeadNodesReport.txt",
    }


def print_table(rows):
    headers = [
        "label",
        "delivery_prob",
        "overhead_ratio",
        "latency_avg",
        "dropped",
        "dead_final",
        "dead_ratio_final",
    ]
    col_widths = {h: len(h) for h in headers}
    for row in rows:
        for h in headers:
            val = row.get(h)
            s = "" if val is None else str(val)
            col_widths[h] = max(col_widths[h], len(s))
    line = " | ".join(h.ljust(col_widths[h]) for h in headers)
    print(line)
    print("-" * len(line))
    for row in rows:
        print(" | ".join((("" if row.get(h) is None else str(row.get(h))).ljust(col_widths[h])) for h in headers))


def write_csv(rows, out_path):
    headers = [
        "label",
        "scenario",
        "delivery_prob",
        "overhead_ratio",
        "latency_avg",
        "dropped",
        "dead_final",
        "dead_ratio_final",
    ]
    with open(out_path, "w") as f:
        f.write(",".join(headers) + "\n")
        for row in rows:
            values = []
            for h in headers:
                v = row.get(h)
                values.append("" if v is None else str(v))
            f.write(",".join(values) + "\n")


def plot_bar_grid(rows, out_png):
    try:
        import matplotlib.pyplot as plt
    except Exception:
        print("matplotlib not available, skipping plots.")
        return
        return

    labels = [r["label"] for r in rows]
    delivery = [r["delivery_prob"] or 0 for r in rows]
    overhead = [r["overhead_ratio"] or 0 for r in rows]
    latency = [r["latency_avg"] or 0 for r in rows]
    dead_ratio = [r["dead_ratio_final"] or 0 for r in rows]

    fig, axs = plt.subplots(2, 2, figsize=(12, 9))
    fig.suptitle("Comparison Metrics (Bar)")

    axs[0, 0].bar(labels, delivery)
    axs[0, 0].set_title("Delivery Ratio")

    axs[0, 1].bar(labels, overhead)
    axs[0, 1].set_title("Overhead Ratio")

    axs[1, 0].bar(labels, latency)
    axs[1, 0].set_title("Average Latency")

    axs[1, 1].bar(labels, dead_ratio)
    axs[1, 1].set_title("Dead Ratio (final)")

    plt.tight_layout(rect=[0, 0.03, 1, 0.95])
    plt.savefig(out_png, dpi=200)
    print(f"Saved plot: {os.path.abspath(out_png)}")


def plot_line_chart(rows, out_png):
    try:
        import matplotlib.pyplot as plt
    except Exception:
        return

    labels = [r["label"] for r in rows]
    metrics = ["delivery_prob", "overhead_ratio", "latency_avg", "dead_ratio_final"]
    values = {m: [r[m] or 0 for r in rows] for m in metrics}

    x = list(range(len(labels)))
    fig, ax = plt.subplots(figsize=(12, 6))
    ax.set_title("Comparison Metrics (Line)")
    for m in metrics:
        ax.plot(x, values[m], marker="o", label=m)
    ax.set_xticks(x)
    ax.set_xticklabels(labels, rotation=20)
    ax.legend()
    ax.grid(axis="y", linestyle="--", alpha=0.6)
    plt.tight_layout()
    plt.savefig(out_png, dpi=200)
    print(f"Saved plot: {os.path.abspath(out_png)}")


def plot_radar_chart(rows, out_png):
    try:
        import matplotlib.pyplot as plt
        import numpy as np
    except Exception:
        return

    labels = [r["label"] for r in rows]
    metrics = ["delivery_prob", "overhead_ratio", "latency_avg", "dead_ratio_final"]
    values = []
    for r in rows:
        values.append([r[m] or 0 for m in metrics])

    # Normalize for radar visibility
    max_vals = [max([v[i] for v in values] + [1e-9]) for i in range(len(metrics))]
    norm_vals = []
    for v in values:
        norm_vals.append([v[i] / max_vals[i] if max_vals[i] > 0 else 0 for i in range(len(metrics))])

    angles = np.linspace(0, 2 * np.pi, len(metrics), endpoint=False).tolist()
    angles += angles[:1]

    fig = plt.figure(figsize=(8, 8))
    ax = fig.add_subplot(111, polar=True)
    ax.set_title("Comparison Metrics (Radar, normalized)")

    for idx, v in enumerate(norm_vals):
        data = v + v[:1]
        ax.plot(angles, data, linewidth=2, label=labels[idx])
        ax.fill(angles, data, alpha=0.15)

    ax.set_xticks(angles[:-1])
    ax.set_xticklabels(metrics)
    ax.set_yticklabels([])
    ax.legend(loc="upper right", bbox_to_anchor=(1.2, 1.1))
    plt.tight_layout()
    plt.savefig(out_png, dpi=200)
    print(f"Saved plot: {os.path.abspath(out_png)}")


def main():
    default_configs = [
        os.path.join("config", "Bench_CCRouting_Energy.txt"),
        os.path.join("config", "Bench_RL_Only.txt"),
        os.path.join("config", "Bench_Prophet_Only.txt"),
    ]
    parser = argparse.ArgumentParser(description="Compare The ONE reports for 3 configs")
    parser.add_argument("--configs", nargs="*", default=default_configs, help="Config file paths")
    parser.add_argument("--out-dir", default="visualization", help="Base output directory")
    args = parser.parse_args()

    rows = []
    for cfg in args.configs:
        conf = parse_config(cfg)
        scenario = conf.get("Scenario.name")
        report_dir = conf.get("Report.reportDir", "reports/")
        if not report_dir.endswith("/"):
            report_dir += "/"
        label = os.path.splitext(os.path.basename(cfg))[0]
        paths = make_report_paths(report_dir, scenario)

        msg = parse_message_stats(paths["message_stats"])
        perf = parse_performance_report(paths["performance"])
        dead = parse_dead_nodes(paths["dead_nodes"])

        row = {
            "label": label,
            "scenario": scenario,
            "delivery_prob": msg["delivery_prob"] if msg["delivery_prob"] is not None else perf["perf_delivery_ratio"],
            "overhead_ratio": msg["overhead_ratio"] if msg["overhead_ratio"] is not None else perf["perf_overhead_ratio"],
            "latency_avg": msg["latency_avg"] if msg["latency_avg"] is not None else perf["perf_latency_avg"],
            "dropped": msg["dropped"],
            "dead_final": dead["dead_final"],
            "dead_ratio_final": dead["dead_ratio_final"],
        }
        rows.append(row)

    print_table(rows)

    ts = datetime.now().strftime("%Y%m%d_%H%M%S")
    out_dir = os.path.join(args.out_dir, ts)
    os.makedirs(out_dir, exist_ok=True)

    csv_path = os.path.join(out_dir, "compare_metrics.csv")
    write_csv(rows, csv_path)
    print(f"Saved CSV: {os.path.abspath(csv_path)}")

    plot_bar_grid(rows, os.path.join(out_dir, "compare_metrics_bar.png"))
    plot_line_chart(rows, os.path.join(out_dir, "compare_metrics_line.png"))
    plot_radar_chart(rows, os.path.join(out_dir, "compare_metrics_radar.png"))


if __name__ == "__main__":
    main()
