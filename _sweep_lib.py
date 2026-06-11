"""
Shared library untuk combined A/B sweep.
Dipakai oleh run_5_combined_A.py dan run_5_combined_B.py.

3 METRIK KUNCI (sesuai requirement user):
    1. survival : sisa node hidup EF > NoEF
    2. longevity: cascade EF lebih lambat (waktu mencapai 50 kematian LEBIH LAMA)
                  -> diukur dari "time to 50 deaths", BUKAN first-death (yang
                     hampir selalu seri karena node energi-terendah sama di
                     kedua skenario akibat seed deterministik).
    3. delivery : delivery_prob EF > NoEF

Output ke folder Z_combined/ bersama, dengan CSV & log terpisah per suffix
(A/B) supaya dua script bisa jalan PARALEL tanpa race condition.
"""

import csv
import subprocess
import time
from datetime import datetime, timedelta
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parent
ITERATIONS_DIR = PROJECT_DIR / "Z_combined"
ONE_BATCH = PROJECT_DIR / "one.bat"
COMPILE_BATCH = PROJECT_DIR / "compile.bat"

# Milestone untuk mengukur kecepatan cascade (longevity).
CASCADE_MILESTONE = 50  # "time to 50 deaths"


# ============================================================================
# CONFIG TEMPLATE
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
    if not filepath or not filepath.exists():
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
                    deaths.append(float(parts[0]))
                except ValueError:
                    continue
    return sorted(deaths)


def parse_message_stats(filepath):
    if not filepath or not filepath.exists():
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


def find_report(directory, scenario_name, suffix):
    matches = list(directory.glob(f"{scenario_name}_{suffix}*.txt"))
    if matches:
        return matches[0]
    matches = list(directory.glob(f"*{suffix}*.txt"))
    return matches[0] if matches else None


def time_to_k_deaths(sorted_times, k):
    """Waktu (detik) saat kematian ke-k terjadi. inf jika tidak pernah capai k
    (artinya cascade lambat / lebih sedikit mati = LEBIH BAIK)."""
    if len(sorted_times) >= k:
        return sorted_times[k - 1]
    return float("inf")


# ============================================================================
# EXEC
# ============================================================================

def make_logger(log_file):
    def log(message):
        ts = datetime.now().strftime("%Y-%m-%d %H:%M:%S")
        line = f"[{ts}] {message}"
        log_file.parent.mkdir(parents=True, exist_ok=True)
        with open(log_file, "a", encoding="utf-8") as f:
            f.write(line + "\n")
        print(line, flush=True)
    return log


def run_simulation_async(config_path, log_path):
    """Start THE ONE in batch mode (-b 1, no GUI)."""
    log_file = open(log_path, "w", encoding="utf-8")
    proc = subprocess.Popen(
        [str(ONE_BATCH), "-b", "1", str(config_path)],
        cwd=str(PROJECT_DIR),
        stdout=log_file, stderr=subprocess.STDOUT, shell=False,
    )
    proc._log_file = log_file
    return proc


def wait_for(procs, names):
    results = {}
    for proc, name in zip(procs, names):
        rc = proc.wait()
        proc._log_file.close()
        results[name] = rc
    return results


def compile_java(log):
    log("Compiling Java sources...")
    result = subprocess.run([str(COMPILE_BATCH)], cwd=str(PROJECT_DIR),
                            capture_output=True, text=True, shell=False)
    if result.returncode != 0:
        log(f"COMPILE FAILED:\n{result.stdout}\n{result.stderr}")
        raise RuntimeError("Compile failed")
    log("Compile OK.")


# ============================================================================
# VERDICT — 3 KEY METRICS (survival, longevity=cascade speed, delivery)
# ============================================================================

CSV_HEADER = [
    "iter_name", "regime", "initialEnergy", "epsilonStart",
    "transmitEnergy", "receiveEnergy", "target_first_death_h",
    "withEF_deaths", "withoutEF_deaths", "survival_diff",
    "withEF_t50_h", "withoutEF_t50_h", "cascade_gap_h",
    "withEF_first_h", "withoutEF_first_h",
    "withEF_delivery", "withoutEF_delivery", "delivery_diff_pct",
    "withEF_latency", "withoutEF_latency",
    "withEF_overhead", "withoutEF_overhead",
    "withEF_hopcount", "withoutEF_hopcount",
    "three_key_count", "duration_min", "rationale", "verdict",
]


def evaluate(ef_deaths, noef_deaths, ef_stats, noef_stats):
    """Return dict berisi semua metrik + verdict."""
    n_ef, n_noef = len(ef_deaths), len(noef_deaths)
    survival_diff = n_noef - n_ef                       # + = EF lebih banyak hidup

    ef_t50 = time_to_k_deaths(ef_deaths, CASCADE_MILESTONE)
    noef_t50 = time_to_k_deaths(noef_deaths, CASCADE_MILESTONE)
    # cascade_gap: EF capai 50 kematian berapa jam LEBIH LAMBAT dari NoEF (+ = EF lebih awet)
    if ef_t50 == float("inf") and noef_t50 == float("inf"):
        cascade_gap_h = 0.0
    elif ef_t50 == float("inf"):
        cascade_gap_h = 99.0   # EF tidak pernah capai 50 -> jauh lebih awet
    elif noef_t50 == float("inf"):
        cascade_gap_h = -99.0
    else:
        cascade_gap_h = (ef_t50 - noef_t50) / 3600.0

    ef_first = ef_deaths[0] / 3600 if ef_deaths else 0.0
    noef_first = noef_deaths[0] / 3600 if noef_deaths else 0.0

    ef_del = ef_stats.get("delivery_prob", 0.0)
    noef_del = noef_stats.get("delivery_prob", 0.0)
    delivery_diff_pct = ((ef_del - noef_del) / max(noef_del, 1e-6)) * 100

    survival_ok = survival_diff > 0
    longevity_ok = cascade_gap_h > 0          # EF cascade lebih lambat
    delivery_ok = (ef_del - noef_del) > 0

    n_ok = sum([survival_ok, longevity_ok, delivery_ok])
    tag = (f"(surv {survival_diff:+d}, casc {cascade_gap_h:+.2f}h, "
           f"deliv {delivery_diff_pct:+.2f}%)")

    total_deaths = n_ef
    if total_deaths < 20:
        verdict = f"TOO_FEW_DEATHS ({total_deaths})"
    elif total_deaths > 190:
        verdict = f"TOO_MANY_DEATHS ({total_deaths})"
    elif n_ok == 3:
        verdict = f"THREE_KEY_WIN {tag}"
    elif survival_ok and delivery_ok:
        verdict = f"SURV+DELIV {tag}"
    elif survival_ok and longevity_ok:
        verdict = f"SURV+CASCADE {tag}"
    elif survival_ok:
        verdict = f"SURVIVAL_ONLY {tag}"
    elif delivery_ok:
        verdict = f"DELIVERY_ONLY {tag}"
    else:
        verdict = f"WORSE {tag}"

    return {
        "n_ef": n_ef, "n_noef": n_noef, "survival_diff": survival_diff,
        "ef_t50_h": ef_t50 / 3600 if ef_t50 != float("inf") else 99.0,
        "noef_t50_h": noef_t50 / 3600 if noef_t50 != float("inf") else 99.0,
        "cascade_gap_h": cascade_gap_h,
        "ef_first_h": ef_first, "noef_first_h": noef_first,
        "ef_del": ef_del, "noef_del": noef_del, "delivery_diff_pct": delivery_diff_pct,
        "three_key": n_ok, "verdict": verdict,
    }


# ============================================================================
# MAIN SWEEP RUNNER
# ============================================================================

def run_sweep(configs, suffix, skip_compile=False, resume=False, out_dir=None):
    """Jalankan daftar config. suffix untuk pisah CSV/log.
    out_dir override folder output (default Z_combined)."""
    global ITERATIONS_DIR
    if out_dir is not None:
        ITERATIONS_DIR = Path(out_dir)
    ITERATIONS_DIR.mkdir(parents=True, exist_ok=True)
    log_file = ITERATIONS_DIR / f"run_{suffix}.log"
    summary_csv = ITERATIONS_DIR / f"summary_{suffix}.csv"
    log = make_logger(log_file)

    log(f"\n{'#'*72}")
    log(f"# COMBINED SWEEP — REGIME {suffix}  ({len(configs)} configs)")
    log(f"# Goal: WithEF unggul di 3 metrik (survival, cascade-longevity, delivery)")
    log(f"# Started: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    log(f"# Output : {ITERATIONS_DIR}  (CSV: summary_{suffix}.csv)")
    log(f"# Mode   : {'RESUME' if resume else 'FRESH'}, "
        f"{'NO_COMPILE' if skip_compile else 'WILL_COMPILE'}")
    log(f"{'#'*72}\n")

    if not skip_compile:
        compile_java(log)

    # init CSV
    if not summary_csv.exists():
        with open(summary_csv, "w", newline="", encoding="utf-8") as f:
            csv.writer(f).writerow(CSV_HEADER)

    total_start = time.time()
    for idx, config in enumerate(configs, start=1):
        _process(config, idx, len(configs), suffix, summary_csv, log, resume)
        elapsed = time.time() - total_start
        remaining = (elapsed / idx) * (len(configs) - idx)
        eta = datetime.now() + timedelta(seconds=remaining)
        log(f"  ETA regime {suffix}: {timedelta(seconds=int(remaining))} "
            f"(finish ~ {eta.strftime('%H:%M:%S')})")

    total_min = (time.time() - total_start) / 60.0
    log(f"\n{'#'*72}")
    log(f"# REGIME {suffix} COMPLETE in {total_min:.1f} min")
    log(f"# CSV: {summary_csv}")
    log(f"# Jalankan: python analyze_combined.py  (untuk hasil gabungan A+B)")
    log(f"{'#'*72}\n")


def _already_done(iter_dir, iter_name):
    ef = find_report(iter_dir / "withEF", f"{iter_name}_WithEF", "NodeDeathReport")
    noef = find_report(iter_dir / "withoutEF", f"{iter_name}_WithoutEF", "NodeDeathReport")
    return ef is not None and noef is not None


def _process(config, idx, total, suffix, summary_csv, log, resume):
    iter_name = config["name"]
    iter_dir = ITERATIONS_DIR / iter_name
    iter_dir.mkdir(parents=True, exist_ok=True)

    log(f"\n{'='*72}")
    log(f"[{suffix}] ITERATION {idx}/{total}: {iter_name}  [{config['regime']}]")
    log(f"  E={config['initialEnergy']}, eps={config['epsilonStart']}, "
        f"TX={config['transmitEnergy']}, RX={config['receiveEnergy']}")
    log(f"  Hypothesis: {config['rationale']}")
    log(f"{'='*72}")

    if resume and _already_done(iter_dir, iter_name):
        log(f"  [SKIP] sudah ada hasil")
        return

    ef_dir = iter_dir / "withEF"
    noef_dir = iter_dir / "withoutEF"
    ef_dir.mkdir(exist_ok=True)
    noef_dir.mkdir(exist_ok=True)

    ef_cfg = iter_dir / f"{iter_name}_WithEF.txt"
    noef_cfg = iter_dir / f"{iter_name}_WithoutEF.txt"
    ef_cfg.write_text(build_config(config, iter_name, "withEF", ef_dir.as_posix()), encoding="utf-8")
    noef_cfg.write_text(build_config(config, iter_name, "withoutEF", noef_dir.as_posix()), encoding="utf-8")

    log(f"  Run WithEF + WithoutEF PARALEL (-b 1)...")
    start = time.time()
    p1 = run_simulation_async(ef_cfg, iter_dir / "withEF_sim.log")
    p2 = run_simulation_async(noef_cfg, iter_dir / "withoutEF_sim.log")
    rc = wait_for([p1, p2], ["EF", "NoEF"])
    dur_min = (time.time() - start) / 60.0
    log(f"  Selesai {dur_min:.1f} min. RC: EF={rc['EF']}, NoEF={rc['NoEF']}")

    base_row = [iter_name, config["regime"], config["initialEnergy"], config["epsilonStart"],
                config["transmitEnergy"], config["receiveEnergy"], config.get("target_first_death_h", "")]

    if rc["EF"] != 0 or rc["NoEF"] != 0:
        log(f"  [ERROR] simulasi gagal")
        _write_row(summary_csv, base_row + [""]*17 + [f"{dur_min:.1f}", config["rationale"], "SIM_FAILED"])
        return

    ef_deaths = parse_node_deaths(find_report(ef_dir, f"{iter_name}_WithEF", "NodeDeathReport"))
    noef_deaths = parse_node_deaths(find_report(noef_dir, f"{iter_name}_WithoutEF", "NodeDeathReport"))
    ef_stats = parse_message_stats(find_report(ef_dir, f"{iter_name}_WithEF", "MessageStatsReport")) or {}
    noef_stats = parse_message_stats(find_report(noef_dir, f"{iter_name}_WithoutEF", "MessageStatsReport")) or {}

    if ef_deaths is None or noef_deaths is None:
        log(f"  [ERROR] parse gagal")
        _write_row(summary_csv, base_row + [""]*17 + [f"{dur_min:.1f}", config["rationale"], "PARSE_FAILED"])
        return

    r = evaluate(ef_deaths, noef_deaths, ef_stats, noef_stats)

    log(f"  RESULTS:")
    log(f"    Deaths    : EF={r['n_ef']}, NoEF={r['n_noef']}  (survival {r['survival_diff']:+d})")
    log(f"    Cascade   : EF capai 50 mati @ {r['ef_t50_h']:.2f}h, NoEF @ {r['noef_t50_h']:.2f}h "
        f"(gap {r['cascade_gap_h']:+.2f}h)")
    log(f"    Delivery  : EF={r['ef_del']:.4f}, NoEF={r['noef_del']:.4f} ({r['delivery_diff_pct']:+.2f}%)")
    log(f"    Overhead  : EF={ef_stats.get('overhead_ratio',0):.2f}, NoEF={noef_stats.get('overhead_ratio',0):.2f}")
    log(f"    >>> 3-KEY: {r['three_key']}/3   VERDICT: {r['verdict']}")

    _write_row(summary_csv, base_row + [
        r["n_ef"], r["n_noef"], r["survival_diff"],
        f"{r['ef_t50_h']:.2f}", f"{r['noef_t50_h']:.2f}", f"{r['cascade_gap_h']:.2f}",
        f"{r['ef_first_h']:.2f}", f"{r['noef_first_h']:.2f}",
        f"{r['ef_del']:.4f}", f"{r['noef_del']:.4f}", f"{r['delivery_diff_pct']:.2f}",
        ef_stats.get("latency_avg", ""), noef_stats.get("latency_avg", ""),
        ef_stats.get("overhead_ratio", ""), noef_stats.get("overhead_ratio", ""),
        ef_stats.get("hopcount_avg", ""), noef_stats.get("hopcount_avg", ""),
        r["three_key"], f"{dur_min:.1f}", config["rationale"], r["verdict"],
    ])


def _write_row(summary_csv, row):
    with open(summary_csv, "a", newline="", encoding="utf-8") as f:
        csv.writer(f).writerow(row)
