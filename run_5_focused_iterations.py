"""
FOCUSED PARAMETER SWEEP: 5 Iterations targeting "BOTH BETTER" outcome
(WithEF unggul di survival DAN delivery DAN overhead).

STRATEGY:
=========
Dari analisis iter05, kita tahu:
- Cascade di 8h+ TERLALU LATE → mayoritas pesan TTL=10min sudah expire/delivered
- WithoutEF tidak "menderita" dari cascade → delivery tidak terganggu
- WithEF buang sedikit delivery karena routing suboptimal

UNTUK "BOTH BETTER", BUTUH:
- Cascade dimulai 4-7h (mid-simulation)
- WithoutEF cyclists mati → delivery anjlok karena hilang relay
- WithEF preserve cyclists → delivery tetap tinggi

PARAMETER PILIHAN:
- E moderate (2200-2500): cascade tidak terlalu awal/late
- TX/RX SANGAT rendah (2.0-2.5 / 0.6-0.8): cascade GRADUAL, tidak catastrophic
- eps 0.25-0.40: EF effective tapi tidak over-greedy

Usage:
    python run_5_focused_iterations.py
    python run_5_focused_iterations.py --skip-compile
    python run_5_focused_iterations.py --resume
"""

import argparse
import csv
import subprocess
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path

# ============================================================================
# PATHS
# ============================================================================
PROJECT_DIR = Path(__file__).resolve().parent
ITERATIONS_DIR = PROJECT_DIR / "Z_focused"
SUMMARY_CSV = ITERATIONS_DIR / "summary.csv"
LOG_FILE = ITERATIONS_DIR / "run.log"

ONE_BATCH = PROJECT_DIR / "one.bat"
COMPILE_BATCH = PROJECT_DIR / "compile.bat"

# ============================================================================
# 5 FOCUSED CONFIGURATIONS — targeting mid-simulation cascade
# ============================================================================
CONFIGS = [
    {
        "name": "focus01_early_gentle",
        "initialEnergy": 2200, "epsilonStart": 0.40,
        "transmitEnergy": 2.5, "receiveEnergy": 0.8,
        "target_first_death_h": "5-6",
        "rationale": "Cascade mid-sim awal + gentle drain → EF punya banyak waktu preserve delivery",
    },
    {
        "name": "focus02_mid_gentle",
        "initialEnergy": 2400, "epsilonStart": 0.40,
        "transmitEnergy": 2.5, "receiveEnergy": 0.8,
        "target_first_death_h": "6-7",
        "rationale": "Cascade mid-sim + gentle drain → balance survival vs delivery",
    },
    {
        "name": "focus03_strong_ef_mid",
        "initialEnergy": 2400, "epsilonStart": 0.25,
        "transmitEnergy": 2.5, "receiveEnergy": 0.8,
        "target_first_death_h": "6-7",
        "rationale": "Same as #2 + EF lebih dominan (eps rendah → 75% exploit Q-table)",
    },
    {
        "name": "focus04_very_gentle",
        "initialEnergy": 2500, "epsilonStart": 0.40,
        "transmitEnergy": 2.0, "receiveEnergy": 0.6,
        "target_first_death_h": "7-8",
        "rationale": "Very gentle drain → EF maksimal punya waktu demonstrasi advantage",
    },
    {
        "name": "focus05_balanced_mid",
        "initialEnergy": 2300, "epsilonStart": 0.30,
        "transmitEnergy": 2.5, "receiveEnergy": 0.8,
        "target_first_death_h": "5-6",
        "rationale": "Kombinasi: gentle drain + strong EF + early cascade → likely sweet spot",
    },
]

# ============================================================================
# CONFIG TEMPLATE GENERATION (identik dengan run_10_iterations.py)
# ============================================================================

def build_config(params, iter_name, router_type, report_dir):
    if router_type == "withEF":
        router_class = "CCRoutingExpert"
        ns = "CCRoutingExpert"
        scenario_name = f"{iter_name}_WithEF"
    else:
        router_class = "CCRoutingWithoutEnergyContext"
        ns = "CCRouting"
        scenario_name = f"{iter_name}_WithoutEF"

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

{ns}.updateInterval = 30
{ns}.baseDiscountGamma = 0.90
{ns}.learningCoeff = 0.9
{ns}.encounterDecayGamma = 0.98
{ns}.qAgingOmega = 0.98
{ns}.epsilonStart = {params['epsilonStart']}
{ns}.epsilonEnd = 0.05
{ns}.epsilonDecayType = exponential
{ns}.simulationTotalTime = 43200

{ns}.initialEnergy = {params['initialEnergy']}
{ns}.scanEnergy = 0.5
{ns}.transmitEnergy = {params['transmitEnergy']}
{ns}.receiveEnergy = {params['receiveEnergy']}

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
# PARSING
# ============================================================================

def parse_node_deaths(filepath):
    if not filepath.exists():
        return None
    deaths = []
    with open(filepath, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) >= 6 and parts[-1] == "DEAD":
                try:
                    deaths.append({"time": float(parts[0]), "addr": int(parts[1])})
                except ValueError:
                    continue
    return deaths


def parse_message_stats(filepath):
    if not filepath.exists():
        return None
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


def find_report(directory, scenario_name, report_suffix):
    matches = list(directory.glob(f"{scenario_name}_{report_suffix}*.txt"))
    if matches:
        return matches[0]
    matches = list(directory.glob(f"*{report_suffix}*.txt"))
    return matches[0] if matches else None


# ============================================================================
# LOGGING / EXEC
# ============================================================================

def log(message):
    ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    line = f"[{ts}] {message}"
    LOG_FILE.parent.mkdir(parents=True, exist_ok=True)
    with open(LOG_FILE, "a", encoding="utf-8") as f:
        f.write(line + "\n")
    print(line, flush=True)


def run_simulation_async(config_path, log_path):
    """Start THE ONE in batch mode (-b 1, no GUI)."""
    log_file = open(log_path, "w", encoding="utf-8")
    proc = subprocess.Popen(
        [str(ONE_BATCH), "-b", "1", str(config_path)],
        cwd=str(PROJECT_DIR),
        stdout=log_file,
        stderr=subprocess.STDOUT,
        shell=False,
    )
    proc._log_file = log_file
    return proc


def wait_for_simulations(procs, names):
    results = {}
    for proc, name in zip(procs, names):
        rc = proc.wait()
        proc._log_file.close()
        results[name] = rc
    return results


def compile_java():
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
        raise RuntimeError("Compile failed")
    log("Compile OK.")


# ============================================================================
# VERDICT (More nuanced — looks at ALL metrics)
# ============================================================================

def evaluate_verdict(ef_deaths, noef_deaths, ef_stats, noef_stats):
    """
    Verdict yang menilai 'BOTH BETTER' criteria:
    - Survivors: WithEF > WithoutEF (more survive)
    - Delivery: WithEF >= WithoutEF (or within 1%)
    - Overhead: WithEF <= WithoutEF (or within 1%)
    """
    if ef_deaths is None or noef_deaths is None:
        return "ERROR"

    survival_diff = len(noef_deaths) - len(ef_deaths)
    total_deaths = len(ef_deaths)

    ef_delivery = ef_stats.get("delivery_prob", 0)
    noef_delivery = noef_stats.get("delivery_prob", 0)
    ef_overhead = ef_stats.get("overhead_ratio", 999)
    noef_overhead = noef_stats.get("overhead_ratio", 999)

    delivery_diff_pct = ((ef_delivery - noef_delivery) / max(noef_delivery, 0.001)) * 100
    overhead_diff_pct = ((noef_overhead - ef_overhead) / max(noef_overhead, 0.001)) * 100

    # Check criteria
    survival_better = survival_diff > 0
    delivery_better_or_equal = delivery_diff_pct >= -1.0  # within 1% tolerance
    overhead_better_or_equal = overhead_diff_pct >= -1.0  # within 1% tolerance

    # Special case: too many or too few deaths
    if total_deaths < 20:
        return f"TOO_FEW_DEATHS ({total_deaths})"
    if total_deaths > 180:
        return f"TOO_MANY_DEATHS ({total_deaths})"

    # The "BOTH BETTER" criteria
    if survival_better and delivery_diff_pct > 0 and overhead_diff_pct > 0:
        return f"PERFECT_WIN (survival +{survival_diff}, delivery +{delivery_diff_pct:.1f}%, overhead -{overhead_diff_pct:.1f}%)"
    if survival_better and delivery_better_or_equal and overhead_better_or_equal:
        return f"BOTH_BETTER (survival +{survival_diff}, delivery {delivery_diff_pct:+.1f}%, overhead {overhead_diff_pct:+.1f}%)"
    if survival_better:
        return f"SURVIVAL_ONLY (+{survival_diff} survivors, but delivery {delivery_diff_pct:+.1f}%, overhead {overhead_diff_pct:+.1f}%)"
    if delivery_diff_pct > 0:
        return f"DELIVERY_ONLY (+{delivery_diff_pct:.1f}% delivery, but {survival_diff:+d} survivors)"
    return f"WORSE (survival {survival_diff:+d}, delivery {delivery_diff_pct:+.1f}%, overhead {overhead_diff_pct:+.1f}%)"


def already_done(iter_dir, iter_name):
    ef_report = find_report(iter_dir / "withEF", f"{iter_name}_WithEF", "NodeDeathReport")
    noef_report = find_report(iter_dir / "withoutEF", f"{iter_name}_WithoutEF", "NodeDeathReport")
    return ef_report is not None and noef_report is not None


def init_summary_csv():
    SUMMARY_CSV.parent.mkdir(parents=True, exist_ok=True)
    if not SUMMARY_CSV.exists():
        with open(SUMMARY_CSV, "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow([
                "iter_name", "initialEnergy", "epsilonStart",
                "transmitEnergy", "receiveEnergy", "target_first_death_h",
                "withEF_deaths", "withoutEF_deaths", "survival_diff",
                "withEF_first_death_h", "withoutEF_first_death_h",
                "withEF_delivery_prob", "withoutEF_delivery_prob", "delivery_diff_pct",
                "withEF_latency_avg", "withoutEF_latency_avg",
                "withEF_overhead", "withoutEF_overhead", "overhead_diff_pct",
                "withEF_hopcount", "withoutEF_hopcount",
                "duration_minutes", "rationale", "verdict",
            ])


def append_summary_row(row):
    with open(SUMMARY_CSV, "a", newline="", encoding="utf-8") as f:
        writer = csv.writer(f)
        writer.writerow(row)


def process_iteration(config, iter_idx, total_iters, args):
    iter_name = config["name"]
    iter_dir = ITERATIONS_DIR / iter_name
    iter_dir.mkdir(parents=True, exist_ok=True)

    log(f"\n{'='*72}")
    log(f"ITERATION {iter_idx}/{total_iters}: {iter_name}")
    log(f"  Params: E={config['initialEnergy']}, eps={config['epsilonStart']}, "
        f"TX={config['transmitEnergy']}, RX={config['receiveEnergy']}")
    log(f"  Target first death: {config['target_first_death_h']}h")
    log(f"  Hypothesis: {config['rationale']}")
    log(f"{'='*72}")

    if args.resume and already_done(iter_dir, iter_name):
        log(f"  [SKIP] Results already exist for {iter_name}")
        return

    withEF_dir = iter_dir / "withEF"
    withoutEF_dir = iter_dir / "withoutEF"
    withEF_dir.mkdir(exist_ok=True)
    withoutEF_dir.mkdir(exist_ok=True)

    withEF_config_path = iter_dir / f"{iter_name}_WithEF.txt"
    withoutEF_config_path = iter_dir / f"{iter_name}_WithoutEF.txt"

    withEF_config_path.write_text(
        build_config(config, iter_name, "withEF", withEF_dir.as_posix()),
        encoding="utf-8",
    )
    withoutEF_config_path.write_text(
        build_config(config, iter_name, "withoutEF", withoutEF_dir.as_posix()),
        encoding="utf-8",
    )

    log(f"  Starting WithEF + WithoutEF in PARALLEL...")
    start = time.time()

    proc_ef = run_simulation_async(withEF_config_path, iter_dir / "withEF_sim.log")
    proc_noef = run_simulation_async(withoutEF_config_path, iter_dir / "withoutEF_sim.log")

    rc = wait_for_simulations([proc_ef, proc_noef], ["WithEF", "WithoutEF"])
    elapsed_min = (time.time() - start) / 60.0
    log(f"  Finished in {elapsed_min:.1f} min. RC: WithEF={rc['WithEF']}, WithoutEF={rc['WithoutEF']}")

    if rc["WithEF"] != 0 or rc["WithoutEF"] != 0:
        log(f"  [ERROR] Simulation failed")
        return

    ef_node_report = find_report(withEF_dir, f"{iter_name}_WithEF", "NodeDeathReport")
    noef_node_report = find_report(withoutEF_dir, f"{iter_name}_WithoutEF", "NodeDeathReport")
    ef_msg_report = find_report(withEF_dir, f"{iter_name}_WithEF", "MessageStatsReport")
    noef_msg_report = find_report(withoutEF_dir, f"{iter_name}_WithoutEF", "MessageStatsReport")

    ef_deaths = parse_node_deaths(ef_node_report) if ef_node_report else None
    noef_deaths = parse_node_deaths(noef_node_report) if noef_node_report else None
    ef_stats = parse_message_stats(ef_msg_report) if ef_msg_report else {}
    noef_stats = parse_message_stats(noef_msg_report) if noef_msg_report else {}

    if ef_deaths is None or noef_deaths is None:
        log(f"  [ERROR] Parse failed")
        return

    ef_first = min((d["time"] for d in ef_deaths), default=0) / 3600
    noef_first = min((d["time"] for d in noef_deaths), default=0) / 3600
    survival_diff = len(noef_deaths) - len(ef_deaths)

    ef_delivery = ef_stats.get("delivery_prob", 0)
    noef_delivery = noef_stats.get("delivery_prob", 0)
    delivery_diff_pct = ((ef_delivery - noef_delivery) / max(noef_delivery, 0.001)) * 100

    ef_overhead = ef_stats.get("overhead_ratio", 0)
    noef_overhead = noef_stats.get("overhead_ratio", 0)
    overhead_diff_pct = ((noef_overhead - ef_overhead) / max(noef_overhead, 0.001)) * 100

    verdict = evaluate_verdict(ef_deaths, noef_deaths, ef_stats, noef_stats)

    log(f"  RESULTS:")
    log(f"    Deaths       : WithEF={len(ef_deaths)}, WithoutEF={len(noef_deaths)} (diff +{survival_diff})")
    log(f"    First death  : WithEF={ef_first:.2f}h, WithoutEF={noef_first:.2f}h")
    log(f"    Delivery     : EF={ef_delivery:.4f}, NoEF={noef_delivery:.4f} ({delivery_diff_pct:+.2f}%)")
    log(f"    Overhead     : EF={ef_overhead:.2f}, NoEF={noef_overhead:.2f} ({overhead_diff_pct:+.2f}%)")
    log(f"    Verdict      : {verdict}")

    append_summary_row([
        iter_name, config["initialEnergy"], config["epsilonStart"],
        config["transmitEnergy"], config["receiveEnergy"], config["target_first_death_h"],
        len(ef_deaths), len(noef_deaths), survival_diff,
        f"{ef_first:.2f}", f"{noef_first:.2f}",
        ef_delivery, noef_delivery, f"{delivery_diff_pct:.2f}",
        ef_stats.get("latency_avg", ""), noef_stats.get("latency_avg", ""),
        ef_overhead, noef_overhead, f"{overhead_diff_pct:.2f}",
        ef_stats.get("hopcount_avg", ""), noef_stats.get("hopcount_avg", ""),
        f"{elapsed_min:.1f}", config["rationale"], verdict,
    ])


def print_recommendation():
    if not SUMMARY_CSV.exists():
        return
    rows = []
    with open(SUMMARY_CSV, "r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            try:
                row["_survival_diff"] = int(row["survival_diff"]) if row["survival_diff"] else -999
                row["_delivery_pct"] = float(row["delivery_diff_pct"]) if row["delivery_diff_pct"] else -999
                row["_total_deaths"] = int(row["withEF_deaths"]) if row["withEF_deaths"] else -1
                rows.append(row)
            except (ValueError, TypeError):
                continue

    if not rows:
        log("\nNo valid results to evaluate.")
        return

    # Hierarchy: PERFECT_WIN > BOTH_BETTER > SURVIVAL_ONLY > best survival diff
    perfect = [r for r in rows if "PERFECT_WIN" in r["verdict"]]
    both = [r for r in rows if "BOTH_BETTER" in r["verdict"]]
    survival = [r for r in rows if "SURVIVAL_ONLY" in r["verdict"]]
    valid = [r for r in rows if 20 <= r["_total_deaths"] <= 180]

    if perfect:
        best = max(perfect, key=lambda r: r["_survival_diff"])
        category = "🏆 PERFECT WIN"
    elif both:
        best = max(both, key=lambda r: r["_survival_diff"])
        category = "✅ BOTH BETTER"
    elif survival:
        best = max(survival, key=lambda r: r["_survival_diff"])
        category = "⚠️  SURVIVAL ONLY (delivery trade-off)"
    elif valid:
        best = max(valid, key=lambda r: r["_survival_diff"])
        category = "📊 BEST AVAILABLE"
    else:
        best = max(rows, key=lambda r: r["_survival_diff"])
        category = "❌ ALL CATASTROPHIC"

    log(f"\n{'='*72}")
    log(f"  REKOMENDASI: {best['iter_name']}  [{category}]")
    log(f"{'='*72}")
    log(f"  Parameter:")
    log(f"    initialEnergy   = {best['initialEnergy']}")
    log(f"    epsilonStart    = {best['epsilonStart']}")
    log(f"    transmitEnergy  = {best['transmitEnergy']}")
    log(f"    receiveEnergy   = {best['receiveEnergy']}")
    log(f"  Hasil:")
    log(f"    WithEF deaths   = {best['withEF_deaths']}")
    log(f"    WithoutEF deaths= {best['withoutEF_deaths']}")
    log(f"    Survival diff   = {best['_survival_diff']:+d}")
    log(f"    Delivery diff   = {best['_delivery_pct']:+.2f}%")
    log(f"    Verdict         = {best['verdict']}")
    log(f"{'='*72}\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-compile", action="store_true")
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()

    ITERATIONS_DIR.mkdir(parents=True, exist_ok=True)
    log(f"\n{'#'*72}")
    log(f"# FOCUSED SWEEP: 5 ITERATIONS (Targeting BOTH BETTER)")
    log(f"# Started: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
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
            log("\n[INTERRUPTED]")
            sys.exit(0)
        except Exception as e:
            log(f"\n[EXCEPTION] {e}")
            import traceback
            log(traceback.format_exc())

        elapsed = time.time() - total_start
        avg_per_iter = elapsed / idx
        remaining = avg_per_iter * (len(CONFIGS) - idx)
        eta = datetime.now() + timedelta(seconds=remaining)
        log(f"  ETA: {timedelta(seconds=int(remaining))} (finish ~ {eta.strftime('%H:%M:%S')})")

    total_min = (time.time() - total_start) / 60.0
    log(f"\n{'#'*72}")
    log(f"# ALL 5 ITERATIONS COMPLETE in {total_min:.1f} minutes")
    log(f"# Summary CSV: {SUMMARY_CSV}")
    log(f"{'#'*72}")

    print_recommendation()


if __name__ == "__main__":
    main()
