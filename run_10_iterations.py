"""
Parameter Sweep: 10 Calculated Iterations of WithEF vs WithoutEF.

Setiap iterasi menguji satu hipotesis spesifik tentang parameter optimal.
Konfigurasi dihitung dari model drain rate empirik:

    r(eps, T) = 0.066 + (eps - 0.15) * 0.073 * (T / 4.0)    [energy/sec]

Dengan FIX 50% spread (sudah di-apply di kode):
    Energy range per node: [initialEnergy * 0.5, initialEnergy]
    T_first_death = (initialEnergy * 0.5) / r
    T_last_death  = initialEnergy / r

Target visualisasi cantik:
    - T_first ≈ 10000-25000s (cascade mulai di pertengahan simulasi)
    - T_last  ≈ 35000-45000s (beberapa node bertahan hingga akhir)
    - Total deaths: 40-100 (visible cascade, banyak yang selamat)
    - WithEF advantage: 10-25 node lebih selamat

Per iterasi: WithEF + WithoutEF run PARALEL (hemat waktu).
10 iterasi sekuensial → estimasi total ~5-10 jam (overnight).

Usage:
    python run_10_iterations.py
    python run_10_iterations.py --skip-compile    # kalau sudah compile
    python run_10_iterations.py --resume          # lanjut yang belum jadi
"""

import argparse
import csv
import os
import re
import subprocess
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path

# ============================================================================
# PATHS
# ============================================================================
PROJECT_DIR = Path(__file__).resolve().parent
ITERATIONS_DIR = PROJECT_DIR / "Z_iterations"
SUMMARY_CSV = ITERATIONS_DIR / "summary.csv"
LOG_FILE = ITERATIONS_DIR / "run.log"

ONE_BATCH = PROJECT_DIR / "one.bat"
COMPILE_BATCH = PROJECT_DIR / "compile.bat"

# ============================================================================
# 10 KONFIGURASI TERKALKULASI
# ============================================================================
# Setiap config menguji hipotesis berbeda. Predicted values dihitung dari
# model drain rate empirik di header.

CONFIGS = [
    {
        "name": "iter01_baseline",
        "initialEnergy": 2000, "epsilonStart": 0.40,
        "transmitEnergy": 4.0, "receiveEnergy": 1.5,
        "T_first_s": 11860, "T_last_s": 23720,
        "rationale": "ANCHOR (current approved config) — confirms fix effect",
    },
    {
        "name": "iter02_higher_energy_2500",
        "initialEnergy": 2500, "epsilonStart": 0.40,
        "transmitEnergy": 4.0, "receiveEnergy": 1.5,
        "T_first_s": 14825, "T_last_s": 29655,
        "rationale": "Test: lebih banyak energy buffer untuk cascade lebih bertahap",
    },
    {
        "name": "iter03_high_energy_3000",
        "initialEnergy": 3000, "epsilonStart": 0.40,
        "transmitEnergy": 4.0, "receiveEnergy": 1.5,
        "T_first_s": 17790, "T_last_s": 35580,
        "rationale": "Test: likely SWEET SPOT — cascade end well within sim time",
    },
    {
        "name": "iter04_very_high_energy_4000",
        "initialEnergy": 4000, "epsilonStart": 0.40,
        "transmitEnergy": 4.0, "receiveEnergy": 1.5,
        "T_first_s": 23720, "T_last_s": 47439,
        "rationale": "Test: energy tinggi → sebagian cyclist bertahan sampai akhir",
    },
    {
        "name": "iter05_lower_transmit_3.0",
        "initialEnergy": 2500, "epsilonStart": 0.40,
        "transmitEnergy": 3.0, "receiveEnergy": 1.0,
        "T_first_s": 15700, "T_last_s": 31407,
        "rationale": "Test: drain lebih lambat via TX/RX energy lebih rendah",
    },
    {
        "name": "iter06_balanced_sweet_spot",
        "initialEnergy": 3000, "epsilonStart": 0.30,
        "transmitEnergy": 3.0, "receiveEnergy": 1.0,
        "T_first_s": 21551, "T_last_s": 43103,
        "rationale": "Test: GENTLE — kombinasi low eps + slow drain + high E",
    },
    {
        "name": "iter07_low_epsilon_0.25",
        "initialEnergy": 2500, "epsilonStart": 0.25,
        "transmitEnergy": 4.0, "receiveEnergy": 1.5,
        "T_first_s": 17053, "T_last_s": 34106,
        "rationale": "Test: STRONG EF — epsilon rendah → EF lebih dominan",
    },
    {
        "name": "iter08_high_epsilon_0.60",
        "initialEnergy": 2500, "epsilonStart": 0.60,
        "transmitEnergy": 4.0, "receiveEnergy": 1.5,
        "T_first_s": 12852, "T_last_s": 25704,
        "rationale": "Test: HIGH EXPLORATION — EF efek minimum, kontrol",
    },
    {
        "name": "iter09_aggressive_E1800",
        "initialEnergy": 1800, "epsilonStart": 0.40,
        "transmitEnergy": 4.0, "receiveEnergy": 1.5,
        "T_first_s": 10676, "T_last_s": 21351,
        "rationale": "Test: AGGRESSIVE — cascade lebih awal, banyak mati",
    },
    {
        "name": "iter10_max_ef_E3500_eps0.20",
        "initialEnergy": 3500, "epsilonStart": 0.20,
        "transmitEnergy": 4.0, "receiveEnergy": 1.5,
        "T_first_s": 25108, "T_last_s": 50215,
        "rationale": "Test: MAX EF DOMINANCE — eps rendah + E tinggi, gradual cascade",
    },
]

# ============================================================================
# CONFIG TEMPLATE GENERATION
# ============================================================================

def build_config(params, iter_name, router_type, report_dir):
    """
    Generate config file content untuk WithEF atau WithoutEF.

    Args:
        params: dict dengan initialEnergy, epsilonStart, transmitEnergy, receiveEnergy
        iter_name: nama iterasi (untuk scenario name)
        router_type: 'withEF' atau 'withoutEF'
        report_dir: path string untuk Report.reportDir (gunakan forward slash)
    """
    if router_type == "withEF":
        router_class = "CCRoutingExpert"
        ns = "CCRoutingExpert"
        scenario_name = f"{iter_name}_WithEF"
    elif router_type == "withoutEF":
        router_class = "CCRoutingWithoutEnergyContext"
        ns = "CCRouting"
        scenario_name = f"{iter_name}_WithoutEF"
    else:
        raise ValueError(f"Unknown router_type: {router_type}")

    # Pastikan report_dir berakhir dengan /
    if not report_dir.endswith("/"):
        report_dir += "/"

    return f"""## Scenario settings
Scenario.name = {scenario_name}
Scenario.simulateConnections = true
Scenario.updateInterval = 0.1
Scenario.endTime = 43200

## Interface settings
btInterface.type = SimpleBroadcastInterface
btInterface.transmitSpeed = 250k
btInterface.transmitRange = 25
btInterface.scanInterval = 120

## Group settings
Scenario.nrofHostGroups = 6

Group.movementModel = ShortestPathMapBasedMovement
Group.router = {router_class}
Group.bufferSize = 50M
Group.waitTime = 0, 120
Group.nrofInterfaces = 1
Group.interface1 = btInterface
Group.speed = 0.5, 1.5
Group.msgTtl = 600

Group1.groupID = p
Group1.nrofHosts = 64

Group2.groupID = c
Group2.speed = 2.7, 13.9
Group2.nrofHosts = 64

Group3.groupID = w
Group3.nrofHosts = 66

Group4.groupID = t
Group4.movementModel = MapRouteMovement
Group4.routeFile = data/tram3.wkt
Group4.routeType = 1
Group4.waitTime = 10, 30
Group4.speed = 7, 10
Group4.bufferSize = 50M
Group4.nrofHosts = 2

Group5.groupID = t
Group5.movementModel = MapRouteMovement
Group5.routeFile = data/tram4.wkt
Group5.routeType = 2
Group5.waitTime = 10, 30
Group5.speed = 7, 10
Group5.bufferSize = 50M
Group5.nrofHosts = 2

Group6.groupID = t
Group6.movementModel = MapRouteMovement
Group6.routeFile = data/tram10.wkt
Group6.routeType = 2
Group6.waitTime = 10, 30
Group6.speed = 7, 10
Group6.bufferSize = 50M
Group6.nrofHosts = 2

# Q-Learning parameters
{ns}.updateInterval = 30
{ns}.baseDiscountGamma = 0.90
{ns}.learningCoeff = 0.9
{ns}.encounterDecayGamma = 0.98
{ns}.qAgingOmega = 0.98
{ns}.epsilonStart = {params['epsilonStart']}
{ns}.epsilonEnd = 0.05
{ns}.epsilonDecayType = exponential
{ns}.simulationTotalTime = 43200

# Energy parameters (50% spread fix sudah di-apply di kode)
{ns}.initialEnergy = {params['initialEnergy']}
{ns}.scanEnergy = 0.5
{ns}.transmitEnergy = {params['transmitEnergy']}
{ns}.receiveEnergy = {params['receiveEnergy']}

# Message generation
Events.nrof = 1
Events1.class = MessageEventGenerator
Events1.interval = 25, 35
Events1.size = 500k, 1M
Events1.hosts = 0, 199
Events1.prefix = M

MovementModel.rngSeed = 1
MovementModel.worldSize = 4500, 3400
MovementModel.warmup = 1000

MapBasedMovement.nrofMapFiles = 4
MapBasedMovement.mapFile1 = data/roads.wkt
MapBasedMovement.mapFile2 = data/main_roads.wkt
MapBasedMovement.mapFile3 = data/pedestrian_paths.wkt
MapBasedMovement.mapFile4 = data/shops.wkt

Report.nrofReports = 3
Report.warmup = 1000
Report.reportDir = {report_dir}
Report.report1 = MessageStatsReport
Report.report2 = ContactTimesReport
Report.report3 = NodeDeathReport
NodeDeathReport.granularity = 60

Optimization.cellSizeMult = 5
Optimization.randomizeUpdateOrder = true

GUI.UnderlayImage.fileName = data/helsinki_underlay.png
GUI.UnderlayImage.offset = 64, 20
GUI.UnderlayImage.scale = 4.75
GUI.UnderlayImage.rotate = -0.015
GUI.EventLogPanel.nrofEvents = 100
"""


# ============================================================================
# RESULT PARSING
# ============================================================================

def parse_node_deaths(filepath):
    """Parse NodeDeathReport file. Return list of dicts."""
    if not filepath.exists():
        return None
    deaths = []
    with open(filepath, "r") as f:
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
                        "router_type": parts[2],
                        "max_energy": float(parts[3]),
                        "energy_ratio": float(parts[4]),
                    })
                except ValueError:
                    continue
    return deaths


def parse_message_stats(filepath):
    """Parse MessageStatsReport file. Return dict of metrics."""
    if not filepath.exists():
        return None
    stats = {}
    with open(filepath, "r") as f:
        for line in f:
            line = line.strip()
            if ":" not in line:
                continue
            key, _, value = line.partition(":")
            key = key.strip()
            value = value.strip()
            try:
                stats[key] = float(value)
            except ValueError:
                stats[key] = value
    return stats


def find_report(directory, scenario_name, report_suffix):
    """Find report file in directory matching scenario name."""
    pattern = f"{scenario_name}_{report_suffix}*.txt"
    matches = list(directory.glob(pattern))
    if matches:
        return matches[0]
    # Fallback: any matching suffix
    matches = list(directory.glob(f"*{report_suffix}*.txt"))
    return matches[0] if matches else None


# ============================================================================
# LOGGING
# ============================================================================

def log(message, console=True):
    """Write to log file and optionally to console."""
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    line = f"[{ts}] {message}"
    LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
    with open(LOG_FILE, "a", encoding="utf-8") as f:
        f.write(line + "\n")
    if console:
        print(line, flush=True)


# ============================================================================
# SIMULATION EXECUTION
# ============================================================================

def run_simulation_async(config_path, log_path):
    """
    Start THE ONE simulation asynchronously in BATCH mode (no GUI).

    Equivalent command: .\\one.bat -b 1 <config.txt>
    The -b 1 flag runs in batch mode (headless, no visualization)
    which is faster and required for unattended overnight runs.

    Returns Popen handle.
    """
    log_file = open(log_path, "w", encoding="utf-8")
    proc = subprocess.Popen(
        [str(ONE_BATCH), "-b", "1", str(config_path)],
        cwd=str(PROJECT_DIR),
        stdout=log_file,
        stderr=subprocess.STDOUT,
        shell=False,
    )
    # Attach log file so we can close it later
    proc._log_file = log_file
    return proc


def wait_for_simulations(procs, names):
    """Wait for all simulation processes. Return dict of name -> returncode."""
    results = {}
    for proc, name in zip(procs, names):
        rc = proc.wait()
        proc._log_file.close()
        results[name] = rc
    return results


# ============================================================================
# COMPILE
# ============================================================================

def compile_java():
    """Run compile.bat. Raise on failure."""
    log("Compiling Java sources...")
    result = subprocess.run(
        [str(COMPILE_BATCH)],
        cwd=str(PROJECT_DIR),
        capture_output=True,
        text=True,
        shell=False,
    )
    if result.returncode != 0:
        log(f"COMPILE FAILED:\n{result.stdout}\n{result.stderr}")
        raise RuntimeError("Compile failed. Check compile_output.txt")
    log("Compile OK.")


# ============================================================================
# MAIN
# ============================================================================

def already_done(iter_dir, iter_name):
    """Check if both WithEF and WithoutEF results already exist."""
    ef_report = find_report(iter_dir / "withEF", f"{iter_name}_WithEF", "NodeDeathReport")
    noef_report = find_report(iter_dir / "withoutEF", f"{iter_name}_WithoutEF", "NodeDeathReport")
    return ef_report is not None and noef_report is not None


def init_summary_csv():
    """Initialize summary CSV with header."""
    SUMMARY_CSV.parent.mkdir(parents=True, exist_ok=True)
    if not SUMMARY_CSV.exists():
        with open(SUMMARY_CSV, "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow([
                "iter_name", "initialEnergy", "epsilonStart",
                "transmitEnergy", "receiveEnergy",
                "predicted_T_first_s", "predicted_T_last_s",
                "withEF_deaths", "withoutEF_deaths", "survival_diff",
                "withEF_first_death_s", "withoutEF_first_death_s",
                "withEF_last_death_s", "withoutEF_last_death_s",
                "withEF_delivery_prob", "withoutEF_delivery_prob",
                "withEF_latency_avg", "withoutEF_latency_avg",
                "withEF_overhead", "withoutEF_overhead",
                "withEF_hopcount", "withoutEF_hopcount",
                "duration_minutes", "rationale", "verdict",
            ])


def append_summary_row(row):
    """Append a row to summary CSV."""
    with open(SUMMARY_CSV, "a", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(row)


def evaluate_verdict(ef_deaths, noef_deaths, ef_stats, noef_stats):
    """Quick verdict on whether this config is interesting."""
    if ef_deaths is None or noef_deaths is None:
        return "ERROR_NO_DATA"

    diff = len(noef_deaths) - len(ef_deaths)
    total_deaths = len(ef_deaths)

    if total_deaths < 20:
        return f"TOO_FEW_DEATHS ({total_deaths})"
    if total_deaths > 180:
        return f"TOO_MANY_DEATHS ({total_deaths})"
    if diff < 0:
        return f"WORSE ({diff} fewer survivors)"
    if diff < 5:
        return f"MARGINAL ({diff} more survivors)"
    if diff < 10:
        return f"OK ({diff} more survivors)"
    if diff < 20:
        return f"GOOD ({diff} more survivors)"
    return f"EXCELLENT ({diff} more survivors)"


def process_iteration(config, iter_idx, total_iters, args):
    """Run one iteration (both WithEF + WithoutEF in parallel) and record results."""
    iter_name = config["name"]
    iter_dir = ITERATIONS_DIR / iter_name
    iter_dir.mkdir(parents=True, exist_ok=True)

    log(f"\n{'='*72}")
    log(f"ITERATION {iter_idx}/{total_iters}: {iter_name}")
    log(f"  Params: initE={config['initialEnergy']}, eps={config['epsilonStart']}, "
        f"TX={config['transmitEnergy']}, RX={config['receiveEnergy']}")
    log(f"  Predicted: T_first={config['T_first_s']}s, T_last={config['T_last_s']}s")
    log(f"  Hypothesis: {config['rationale']}")
    log(f"{'='*72}")

    if args.resume and already_done(iter_dir, iter_name):
        log(f"  [SKIP] Results already exist for {iter_name}")
        return

    # Setup directories
    withEF_dir = iter_dir / "withEF"
    withoutEF_dir = iter_dir / "withoutEF"
    withEF_dir.mkdir(exist_ok=True)
    withoutEF_dir.mkdir(exist_ok=True)

    # Generate config files (use POSIX-style path for THE ONE)
    withEF_config_path = iter_dir / f"{iter_name}_WithEF.txt"
    withoutEF_config_path = iter_dir / f"{iter_name}_WithoutEF.txt"

    ef_report_dir = withEF_dir.as_posix()
    noef_report_dir = withoutEF_dir.as_posix()

    withEF_config_path.write_text(
        build_config(config, iter_name, "withEF", ef_report_dir),
        encoding="utf-8",
    )
    withoutEF_config_path.write_text(
        build_config(config, iter_name, "withoutEF", noef_report_dir),
        encoding="utf-8",
    )

    # Run simulations in PARALLEL
    log(f"  Starting WithEF + WithoutEF in PARALLEL...")
    start = time.time()

    ef_log = iter_dir / "withEF_sim.log"
    noef_log = iter_dir / "withoutEF_sim.log"

    proc_ef = run_simulation_async(withEF_config_path, ef_log)
    proc_noef = run_simulation_async(withoutEF_config_path, noef_log)

    rc = wait_for_simulations([proc_ef, proc_noef], ["WithEF", "WithoutEF"])

    elapsed_min = (time.time() - start) / 60.0
    log(f"  Finished in {elapsed_min:.1f} min. RC: WithEF={rc['WithEF']}, WithoutEF={rc['WithoutEF']}")

    if rc["WithEF"] != 0 or rc["WithoutEF"] != 0:
        log(f"  [ERROR] One or both simulations failed. Check sim logs in {iter_dir}")
        append_summary_row([
            iter_name, config["initialEnergy"], config["epsilonStart"],
            config["transmitEnergy"], config["receiveEnergy"],
            config["T_first_s"], config["T_last_s"],
            "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
            f"{elapsed_min:.1f}", config["rationale"], "SIMULATION_FAILED",
        ])
        return

    # Parse results
    ef_node_report = find_report(withEF_dir, f"{iter_name}_WithEF", "NodeDeathReport")
    noef_node_report = find_report(withoutEF_dir, f"{iter_name}_WithoutEF", "NodeDeathReport")
    ef_msg_report = find_report(withEF_dir, f"{iter_name}_WithEF", "MessageStatsReport")
    noef_msg_report = find_report(withoutEF_dir, f"{iter_name}_WithoutEF", "MessageStatsReport")

    ef_deaths = parse_node_deaths(ef_node_report) if ef_node_report else None
    noef_deaths = parse_node_deaths(noef_node_report) if noef_node_report else None
    ef_stats = parse_message_stats(ef_msg_report) if ef_msg_report else {}
    noef_stats = parse_message_stats(noef_msg_report) if noef_msg_report else {}

    if ef_deaths is None or noef_deaths is None:
        log(f"  [ERROR] Could not parse NodeDeathReport")
        append_summary_row([
            iter_name, config["initialEnergy"], config["epsilonStart"],
            config["transmitEnergy"], config["receiveEnergy"],
            config["T_first_s"], config["T_last_s"],
            "", "", "", "", "", "", "", "", "", "", "", "", "", "", "",
            f"{elapsed_min:.1f}", config["rationale"], "PARSE_FAILED",
        ])
        return

    ef_first = min((d["time"] for d in ef_deaths), default=0)
    ef_last = max((d["time"] for d in ef_deaths), default=0)
    noef_first = min((d["time"] for d in noef_deaths), default=0)
    noef_last = max((d["time"] for d in noef_deaths), default=0)

    survival_diff = len(noef_deaths) - len(ef_deaths)
    verdict = evaluate_verdict(ef_deaths, noef_deaths, ef_stats, noef_stats)

    log(f"  RESULTS:")
    log(f"    Deaths: WithEF={len(ef_deaths)}, WithoutEF={len(noef_deaths)}")
    log(f"    Survival diff (EF advantage): {survival_diff:+d}")
    log(f"    First death: WithEF={ef_first:.0f}s, WithoutEF={noef_first:.0f}s")
    log(f"    Delivery: WithEF={ef_stats.get('delivery_prob', 0):.4f}, "
        f"WithoutEF={noef_stats.get('delivery_prob', 0):.4f}")
    log(f"    Verdict: {verdict}")

    append_summary_row([
        iter_name, config["initialEnergy"], config["epsilonStart"],
        config["transmitEnergy"], config["receiveEnergy"],
        config["T_first_s"], config["T_last_s"],
        len(ef_deaths), len(noef_deaths), survival_diff,
        f"{ef_first:.1f}", f"{noef_first:.1f}",
        f"{ef_last:.1f}", f"{noef_last:.1f}",
        ef_stats.get("delivery_prob", ""), noef_stats.get("delivery_prob", ""),
        ef_stats.get("latency_avg", ""), noef_stats.get("latency_avg", ""),
        ef_stats.get("overhead_ratio", ""), noef_stats.get("overhead_ratio", ""),
        ef_stats.get("hopcount_avg", ""), noef_stats.get("hopcount_avg", ""),
        f"{elapsed_min:.1f}", config["rationale"], verdict,
    ])


def print_recommendation():
    """At the end, find best config and print recommendation."""
    if not SUMMARY_CSV.exists():
        return
    rows = []
    with open(SUMMARY_CSV, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            try:
                row["_survival_diff"] = int(row["survival_diff"]) if row["survival_diff"] else -999
                row["_total_deaths"] = int(row["withEF_deaths"]) if row["withEF_deaths"] else -1
                rows.append(row)
            except (ValueError, TypeError):
                continue

    if not rows:
        log("\nNo valid results to evaluate.")
        return

    # Best: highest survival_diff, with 30-150 total deaths
    valid = [r for r in rows if 30 <= r["_total_deaths"] <= 150]
    if not valid:
        valid = rows

    best = max(valid, key=lambda r: r["_survival_diff"])

    log(f"\n{'='*72}")
    log(f"  REKOMENDASI: {best['iter_name']}")
    log(f"{'='*72}")
    log(f"  Parameter:")
    log(f"    initialEnergy   = {best['initialEnergy']}")
    log(f"    epsilonStart    = {best['epsilonStart']}")
    log(f"    transmitEnergy  = {best['transmitEnergy']}")
    log(f"    receiveEnergy   = {best['receiveEnergy']}")
    log(f"  Hasil:")
    log(f"    WithEF deaths   = {best['withEF_deaths']}")
    log(f"    WithoutEF deaths= {best['withoutEF_deaths']}")
    log(f"    Survival diff   = {best['_survival_diff']:+d} (positive = WithEF unggul)")
    log(f"    Delivery EF     = {best['withEF_delivery_prob']}")
    log(f"    Delivery NoEF   = {best['withoutEF_delivery_prob']}")
    log(f"    Verdict         = {best['verdict']}")
    log(f"\n  Pakai parameter ini di config final-mu untuk visualisasi cantik!")
    log(f"{'='*72}\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-compile", action="store_true",
                        help="Skip compile step (use existing target/)")
    parser.add_argument("--resume", action="store_true",
                        help="Skip iterations that already have results")
    args = parser.parse_args()

    ITERATIONS_DIR.mkdir(parents=True, exist_ok=True)
    log(f"\n{'#'*72}")
    log(f"# PARAMETER SWEEP: 10 ITERATIONS")
    log(f"# Started: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    log(f"# Project: {PROJECT_DIR}")
    log(f"# Output : {ITERATIONS_DIR}")
    log(f"# Mode   : {'RESUME' if args.resume else 'FRESH'}, "
        f"{'NO_COMPILE' if args.skip_compile else 'WILL_COMPILE'}")
    log(f"{'#'*72}\n")

    if not args.skip_compile:
        try:
            compile_java()
        except Exception as e:
            log(f"COMPILE ERROR: {e}")
            sys.exit(1)

    init_summary_csv()

    total_start = time.time()
    for idx, config in enumerate(CONFIGS, start=1):
        try:
            process_iteration(config, idx, len(CONFIGS), args)
        except KeyboardInterrupt:
            log("\n[INTERRUPTED] User pressed Ctrl+C. Stopping.")
            sys.exit(0)
        except Exception as e:
            log(f"\n[EXCEPTION] in iteration {idx}: {e}")
            import traceback
            log(traceback.format_exc())

        # Print ETA
        elapsed = time.time() - total_start
        avg_per_iter = elapsed / idx
        remaining = avg_per_iter * (len(CONFIGS) - idx)
        eta = datetime.now() + timedelta(seconds=remaining)
        log(f"  ETA: {timedelta(seconds=int(remaining))} (finish ~ {eta.strftime('%H:%M:%S')})")

    total_min = (time.time() - total_start) / 60.0
    log(f"\n{'#'*72}")
    log(f"# ALL ITERATIONS COMPLETE in {total_min:.1f} minutes")
    log(f"# Summary CSV: {SUMMARY_CSV}")
    log(f"{'#'*72}")

    print_recommendation()


if __name__ == "__main__":
    main()
