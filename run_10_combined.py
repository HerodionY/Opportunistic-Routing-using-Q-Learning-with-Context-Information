"""
COMBINED A/B SWEEP: 10 Iterations across TWO regimes.

TUJUAN: Cari config di mana WithEF unggul di 3 METRIK KUNCI:
    1. Sisa node lebih banyak (survival)
    2. Bertahan lebih lama / kurva lebih landai (longevity)
    3. Delivery probability lebih bagus

DUA REGIME (5 config masing-masing, perbandingan adil):

REGIME A — "GENTLE / MID-CASCADE" (option 1):
    Energy moderate (2200-2500) + drain SANGAT rendah (TX 2.0-2.5, RX 0.6-0.8)
    Hipotesis: cascade jatuh di tengah simulasi (5-8h) → WithoutEF kehilangan
    relay penting di saat banyak pesan beredar → delivery-nya jeblok → EF menang.
    Potensi: margin survival BESAR. Risk: gentle drain kemarin (iter5/6) malah
    KALAH delivery — jadi ini taruhan pada regime yang belum terbukti.

REGIME B — "HIGH-ENERGY / LATE-CASCADE" (option 2):
    Energy tinggi (3500-4500) + drain standar (TX 4.0, RX 1.5)
    Grid: energy {3500,4000,4500} x epsilon {0.20,0.25,0.30}, bias ke eps rendah.
    Basis: iter4 (E4000,eps0.40) & iter10 (E3500,eps0.20) DARI run kemarin SUDAH
    menang delivery (+0.0014, +0.0063). eps rendah = EF lebih dipakai = delivery
    advantage lebih besar. Potensi: 3 metrik kena RELIABLE, tapi margin TIPIS.

Semua 10 config DISTINCT dari run kemarin & satu sama lain (rngSeed=1 fixed →
param identik = hasil identik, jadi tidak ada run yang mubazir).

Estimasi runtime: ~7-8 jam (overnight). Output: Z_combined/

Usage:
    python run_10_combined.py
    python run_10_combined.py --skip-compile
    python run_10_combined.py --resume
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
ITERATIONS_DIR = PROJECT_DIR / "Z_combined"
SUMMARY_CSV = ITERATIONS_DIR / "summary.csv"
LOG_FILE = ITERATIONS_DIR / "run.log"

ONE_BATCH = PROJECT_DIR / "one.bat"
COMPILE_BATCH = PROJECT_DIR / "compile.bat"

# ============================================================================
# 10 CONFIGURATIONS — 5 GENTLE/MID (A) + 5 HIGH/LATE (B)
# ============================================================================
CONFIGS = [
    # ----- REGIME A: GENTLE / MID-CASCADE (big-margin gamble) -----
    {
        "name": "A1_gentle_E2200",
        "regime": "A_gentle_mid",
        "initialEnergy": 2200, "epsilonStart": 0.40,
        "transmitEnergy": 2.5, "receiveEnergy": 0.8,
        "target_first_death_h": "5-6",
        "rationale": "Cascade mid-sim awal + gentle drain, EF banyak waktu preserve delivery",
    },
    {
        "name": "A2_gentle_E2400",
        "regime": "A_gentle_mid",
        "initialEnergy": 2400, "epsilonStart": 0.40,
        "transmitEnergy": 2.5, "receiveEnergy": 0.8,
        "target_first_death_h": "6-7",
        "rationale": "Cascade mid-sim + gentle drain, balance survival vs delivery",
    },
    {
        "name": "A3_gentle_strongEF",
        "regime": "A_gentle_mid",
        "initialEnergy": 2400, "epsilonStart": 0.25,
        "transmitEnergy": 2.5, "receiveEnergy": 0.8,
        "target_first_death_h": "6-7",
        "rationale": "Sama A2 + EF dominan (eps0.25 -> 75% exploit Q-table)",
    },
    {
        "name": "A4_very_gentle",
        "regime": "A_gentle_mid",
        "initialEnergy": 2500, "epsilonStart": 0.40,
        "transmitEnergy": 2.0, "receiveEnergy": 0.6,
        "target_first_death_h": "7-8",
        "rationale": "Drain paling rendah, EF maksimal waktu demonstrasi advantage",
    },
    {
        "name": "A5_balanced_mid",
        "regime": "A_gentle_mid",
        "initialEnergy": 2300, "epsilonStart": 0.30,
        "transmitEnergy": 2.5, "receiveEnergy": 0.8,
        "target_first_death_h": "5-6",
        "rationale": "Gentle drain + strong EF + early-mid cascade, likely sweet spot regime A",
    },
    # ----- REGIME B: HIGH-ENERGY / LATE-CASCADE (reliable thin-margin) -----
    {
        "name": "B1_E3500_eps25",
        "regime": "B_high_late",
        "initialEnergy": 3500, "epsilonStart": 0.25,
        "transmitEnergy": 4.0, "receiveEnergy": 1.5,
        "target_first_death_h": "~10",
        "rationale": "Dekat iter10 winner (E3500,eps0.20) dgn eps sedikit naik",
    },
    {
        "name": "B2_E4000_eps20",
        "regime": "B_high_late",
        "initialEnergy": 4000, "epsilonStart": 0.20,
        "transmitEnergy": 4.0, "receiveEnergy": 1.5,
        "target_first_death_h": "~10.3",
        "rationale": "Energy iter4 + EF terkuat (eps0.20) -> likely best delivery+survivors",
    },
    {
        "name": "B3_E4000_eps25",
        "regime": "B_high_late",
        "initialEnergy": 4000, "epsilonStart": 0.25,
        "transmitEnergy": 4.0, "receiveEnergy": 1.5,
        "target_first_death_h": "~10.3",
        "rationale": "Energy iter4 + EF moderat, titik tengah grid",
    },
    {
        "name": "B4_E4500_eps20",
        "regime": "B_high_late",
        "initialEnergy": 4500, "epsilonStart": 0.20,
        "transmitEnergy": 4.0, "receiveEnergy": 1.5,
        "target_first_death_h": "~10.7",
        "rationale": "Energy tertinggi + EF terkuat -> survivors terbanyak, cascade paling lambat",
    },
    {
        "name": "B5_E4500_eps30",
        "regime": "B_high_late",
        "initialEnergy": 4500, "epsilonStart": 0.30,
        "transmitEnergy": 4.0, "receiveEnergy": 1.5,
        "target_first_death_h": "~10.7",
        "rationale": "Energy tinggi + EF moderat, cek apakah eps lebih tinggi masih menang delivery",
    },
]

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
                    deaths.append({"time": float(parts[0]), "addr": int(parts[1])})
                except ValueError:
                    continue
    return deaths


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
        capture_output=True, text=True, shell=False,
    )
    if result.returncode != 0:
        log(f"COMPILE FAILED:\n{result.stdout}\n{result.stderr}")
        raise RuntimeError("Compile failed")
    log("Compile OK.")


# ============================================================================
# VERDICT — focused on USER's 3 KEY METRICS
#   1. survival   : WithEF survivors > WithoutEF
#   2. longevity  : WithEF first death >= WithoutEF (lasts longer / better curve)
#   3. delivery   : WithEF delivery_prob > WithoutEF
# ============================================================================

def evaluate_verdict(ef_deaths, noef_deaths, ef_stats, noef_stats):
    if ef_deaths is None or noef_deaths is None:
        return "ERROR"

    survival_diff = len(noef_deaths) - len(ef_deaths)        # + = EF better
    total_deaths = len(ef_deaths)

    ef_first = min((d["time"] for d in ef_deaths), default=0)
    noef_first = min((d["time"] for d in noef_deaths), default=0)
    longevity_diff = ef_first - noef_first                    # + = EF lasts longer

    ef_del = ef_stats.get("delivery_prob", 0)
    noef_del = noef_stats.get("delivery_prob", 0)
    delivery_diff = ef_del - noef_del                         # + = EF better

    if total_deaths < 20:
        return f"TOO_FEW_DEATHS ({total_deaths})"
    if total_deaths > 185:
        return f"TOO_MANY_DEATHS ({total_deaths})"

    survival_ok = survival_diff > 0
    longevity_ok = longevity_diff >= 0
    delivery_ok = delivery_diff > 0

    n_ok = sum([survival_ok, longevity_ok, delivery_ok])

    tag = f"(surv {survival_diff:+d}, longev {longevity_diff/3600:+.2f}h, deliv {delivery_diff*100:+.2f}%)"

    if n_ok == 3:
        return f"THREE_KEY_WIN {tag}"
    if survival_ok and delivery_ok:
        return f"SURV+DELIV {tag}"          # 2 of 3, missing longevity
    if survival_ok and longevity_ok:
        return f"SURV+LONGEV {tag}"          # 2 of 3, missing delivery
    if survival_ok:
        return f"SURVIVAL_ONLY {tag}"
    if delivery_ok:
        return f"DELIVERY_ONLY {tag}"
    return f"WORSE {tag}"


def already_done(iter_dir, iter_name):
    ef = find_report(iter_dir / "withEF", f"{iter_name}_WithEF", "NodeDeathReport")
    noef = find_report(iter_dir / "withoutEF", f"{iter_name}_WithoutEF", "NodeDeathReport")
    return ef is not None and noef is not None


def init_summary_csv():
    SUMMARY_CSV.parent.mkdir(parents=True, exist_ok=True)
    if not SUMMARY_CSV.exists():
        with open(SUMMARY_CSV, "w", newline="", encoding="utf-8") as f:
            writer = csv.writer(f)
            writer.writerow([
                "iter_name", "regime", "initialEnergy", "epsilonStart",
                "transmitEnergy", "receiveEnergy", "target_first_death_h",
                "withEF_deaths", "withoutEF_deaths", "survival_diff",
                "withEF_first_death_h", "withoutEF_first_death_h", "longevity_diff_h",
                "withEF_delivery", "withoutEF_delivery", "delivery_diff_pct",
                "withEF_latency", "withoutEF_latency",
                "withEF_overhead", "withoutEF_overhead",
                "withEF_hopcount", "withoutEF_hopcount",
                "three_key_count", "duration_min", "rationale", "verdict",
            ])


def append_summary_row(row):
    with open(SUMMARY_CSV, "a", newline="", encoding="utf-8") as f:
        csv.writer(f).writerow(row)


def process_iteration(config, iter_idx, total_iters, args):
    iter_name = config["name"]
    iter_dir = ITERATIONS_DIR / iter_name
    iter_dir.mkdir(parents=True, exist_ok=True)

    log(f"\n{'='*72}")
    log(f"ITERATION {iter_idx}/{total_iters}: {iter_name}  [{config['regime']}]")
    log(f"  Params: E={config['initialEnergy']}, eps={config['epsilonStart']}, "
        f"TX={config['transmitEnergy']}, RX={config['receiveEnergy']}")
    log(f"  Target first death: {config['target_first_death_h']}h")
    log(f"  Hypothesis: {config['rationale']}")
    log(f"{'='*72}")

    if args.resume and already_done(iter_dir, iter_name):
        log(f"  [SKIP] Results already exist")
        return

    withEF_dir = iter_dir / "withEF"
    withoutEF_dir = iter_dir / "withoutEF"
    withEF_dir.mkdir(exist_ok=True)
    withoutEF_dir.mkdir(exist_ok=True)

    ef_cfg = iter_dir / f"{iter_name}_WithEF.txt"
    noef_cfg = iter_dir / f"{iter_name}_WithoutEF.txt"
    ef_cfg.write_text(build_config(config, iter_name, "withEF", withEF_dir.as_posix()), encoding="utf-8")
    noef_cfg.write_text(build_config(config, iter_name, "withoutEF", withoutEF_dir.as_posix()), encoding="utf-8")

    log(f"  Starting WithEF + WithoutEF in PARALLEL (-b 1, no GUI)...")
    start = time.time()
    p_ef = run_simulation_async(ef_cfg, iter_dir / "withEF_sim.log")
    p_noef = run_simulation_async(noef_cfg, iter_dir / "withoutEF_sim.log")
    rc = wait_for_simulations([p_ef, p_noef], ["WithEF", "WithoutEF"])
    elapsed_min = (time.time() - start) / 60.0
    log(f"  Finished in {elapsed_min:.1f} min. RC: EF={rc['WithEF']}, NoEF={rc['WithoutEF']}")

    if rc["WithEF"] != 0 or rc["WithoutEF"] != 0:
        log(f"  [ERROR] Simulation failed — check sim logs in {iter_dir}")
        append_summary_row([iter_name, config["regime"], config["initialEnergy"],
                            config["epsilonStart"], config["transmitEnergy"], config["receiveEnergy"],
                            config["target_first_death_h"], "", "", "", "", "", "", "", "", "",
                            "", "", "", "", "", "", "", f"{elapsed_min:.1f}",
                            config["rationale"], "SIM_FAILED"])
        return

    ef_deaths = parse_node_deaths(find_report(withEF_dir, f"{iter_name}_WithEF", "NodeDeathReport"))
    noef_deaths = parse_node_deaths(find_report(withoutEF_dir, f"{iter_name}_WithoutEF", "NodeDeathReport"))
    ef_stats = parse_message_stats(find_report(withEF_dir, f"{iter_name}_WithEF", "MessageStatsReport")) or {}
    noef_stats = parse_message_stats(find_report(withoutEF_dir, f"{iter_name}_WithoutEF", "MessageStatsReport")) or {}

    if ef_deaths is None or noef_deaths is None:
        log(f"  [ERROR] Parse failed")
        append_summary_row([iter_name, config["regime"], config["initialEnergy"],
                            config["epsilonStart"], config["transmitEnergy"], config["receiveEnergy"],
                            config["target_first_death_h"], "", "", "", "", "", "", "", "", "",
                            "", "", "", "", "", "", "", f"{elapsed_min:.1f}",
                            config["rationale"], "PARSE_FAILED"])
        return

    ef_first = min((d["time"] for d in ef_deaths), default=0) / 3600
    noef_first = min((d["time"] for d in noef_deaths), default=0) / 3600
    survival_diff = len(noef_deaths) - len(ef_deaths)
    longevity_diff = ef_first - noef_first

    ef_del = ef_stats.get("delivery_prob", 0)
    noef_del = noef_stats.get("delivery_prob", 0)
    delivery_diff_pct = ((ef_del - noef_del) / max(noef_del, 1e-6)) * 100

    verdict = evaluate_verdict(ef_deaths, noef_deaths, ef_stats, noef_stats)
    three_key = sum([survival_diff > 0, longevity_diff >= 0, (ef_del - noef_del) > 0])

    log(f"  RESULTS:")
    log(f"    Deaths     : EF={len(ef_deaths)}, NoEF={len(noef_deaths)}  (survival diff {survival_diff:+d})")
    log(f"    First death: EF={ef_first:.2f}h, NoEF={noef_first:.2f}h  (longevity {longevity_diff:+.2f}h)")
    log(f"    Delivery   : EF={ef_del:.4f}, NoEF={noef_del:.4f}  ({delivery_diff_pct:+.2f}%)")
    log(f"    Overhead   : EF={ef_stats.get('overhead_ratio',0):.2f}, NoEF={noef_stats.get('overhead_ratio',0):.2f}")
    log(f"    >>> 3-KEY metrics passed: {three_key}/3   Verdict: {verdict}")

    append_summary_row([
        iter_name, config["regime"], config["initialEnergy"], config["epsilonStart"],
        config["transmitEnergy"], config["receiveEnergy"], config["target_first_death_h"],
        len(ef_deaths), len(noef_deaths), survival_diff,
        f"{ef_first:.2f}", f"{noef_first:.2f}", f"{longevity_diff:.2f}",
        f"{ef_del:.4f}", f"{noef_del:.4f}", f"{delivery_diff_pct:.2f}",
        ef_stats.get("latency_avg", ""), noef_stats.get("latency_avg", ""),
        ef_stats.get("overhead_ratio", ""), noef_stats.get("overhead_ratio", ""),
        ef_stats.get("hopcount_avg", ""), noef_stats.get("hopcount_avg", ""),
        three_key, f"{elapsed_min:.1f}", config["rationale"], verdict,
    ])


def print_recommendation():
    if not SUMMARY_CSV.exists():
        return
    rows = []
    with open(SUMMARY_CSV, "r", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            try:
                row["_surv"] = int(row["survival_diff"]) if row["survival_diff"] else -999
                row["_deliv"] = float(row["delivery_diff_pct"]) if row["delivery_diff_pct"] else -999
                row["_longev"] = float(row["longevity_diff_h"]) if row["longevity_diff_h"] else -999
                row["_3key"] = int(row["three_key_count"]) if row["three_key_count"] else 0
                row["_deaths"] = int(row["withEF_deaths"]) if row["withEF_deaths"] else -1
                rows.append(row)
            except (ValueError, TypeError):
                continue
    if not rows:
        log("\nNo valid results.")
        return

    def show(r, label):
        log(f"\n  [{label}] {r['iter_name']}  ({r['regime']})")
        log(f"      E={r['initialEnergy']}, eps={r['epsilonStart']}, "
            f"TX={r['transmitEnergy']}, RX={r['receiveEnergy']}")
        log(f"      survival {r['_surv']:+d}  |  longevity {r['_longev']:+.2f}h  |  "
            f"delivery {r['_deliv']:+.2f}%  |  3-key {r['_3key']}/3")
        log(f"      verdict: {r['verdict']}")

    # Ranking utama: jumlah 3-key metric, lalu survival, lalu delivery
    def composite(r):
        return (r["_3key"], r["_surv"], r["_deliv"])

    valid = [r for r in rows if 20 <= r["_deaths"] <= 185]
    pool = valid if valid else rows

    log(f"\n{'='*72}")
    log(f"  HASIL AKHIR — 3 METRIK KUNCI (survival, longevity, delivery)")
    log(f"{'='*72}")

    three_key_wins = [r for r in pool if r["_3key"] == 3]
    if three_key_wins:
        best = max(three_key_wins, key=composite)
        show(best, "JUARA — 3-KEY WIN")
        log(f"\n  Total config yang kena KETIGA metrik: {len(three_key_wins)}")
        for r in sorted(three_key_wins, key=composite, reverse=True):
            log(f"    - {r['iter_name']:<22} surv {r['_surv']:+d}, "
                f"longev {r['_longev']:+.2f}h, deliv {r['_deliv']:+.2f}%")
    else:
        log(f"\n  Tidak ada yang kena KETIGA metrik sekaligus.")
        best = max(pool, key=composite)
        show(best, "TERBAIK (2/3 metrik)")

    # Best per regime untuk perbandingan A/B
    log(f"\n  {'-'*68}")
    log(f"  PERBANDINGAN REGIME (A gentle/mid  vs  B high/late):")
    for regime, label in [("A_gentle_mid", "A gentle/mid"), ("B_high_late", "B high/late")]:
        reg_rows = [r for r in pool if r["regime"] == regime]
        if reg_rows:
            best_r = max(reg_rows, key=composite)
            show(best_r, f"BEST REGIME {label}")

    log(f"\n{'='*72}\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-compile", action="store_true")
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()

    ITERATIONS_DIR.mkdir(parents=True, exist_ok=True)
    log(f"\n{'#'*72}")
    log(f"# COMBINED A/B SWEEP: 10 ITERATIONS (5 gentle/mid + 5 high/late)")
    log(f"# Goal   : WithEF unggul di 3 metrik (survival, longevity, delivery)")
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
            log("\n[INTERRUPTED] Ctrl+C")
            sys.exit(0)
        except Exception as e:
            log(f"\n[EXCEPTION] iter {idx}: {e}")
            import traceback
            log(traceback.format_exc())

        elapsed = time.time() - total_start
        remaining = (elapsed / idx) * (len(CONFIGS) - idx)
        eta = datetime.now() + timedelta(seconds=remaining)
        log(f"  ETA: {timedelta(seconds=int(remaining))} (finish ~ {eta.strftime('%H:%M:%S')})")

    total_min = (time.time() - total_start) / 60.0
    log(f"\n{'#'*72}")
    log(f"# ALL 10 ITERATIONS COMPLETE in {total_min:.1f} min")
    log(f"# Summary: {SUMMARY_CSV}")
    log(f"{'#'*72}")
    print_recommendation()


if __name__ == "__main__":
    main()
