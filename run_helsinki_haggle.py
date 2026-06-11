"""
HELSINKI ALA-HAGGLE — meniru rezim parameter Haggle (yang TERBUKTI menang &
disetujui dospem) ke dataset Helsinki, secara FAIR & KONSISTEN.

PRINSIP KONSISTENSI (anti-bantai sidang):
  - SEMUA parameter Q-learning DICOPY PERSIS dari config Haggle:
        baseDiscountGamma=0.6, learningCoeff=0.8, epsilonStart=1.0,
        epsilonEnd=0.05, encounterDecayGamma=0.9999, qAgingOmega=0.9999,
        updateInterval=300, msgTtl=2880, scanEnergy=0.1, receiveEnergy=1.5
    -> algoritma 100% identik antar dataset. Tidak bisa diserang.
  - Energi tetap HOMOGEN dengan spread -200 (sama Haggle). Konsisten.
  - initialEnergy dibuat KECIL (700-1500) supaya spread -200 menghasilkan
    variasi relatif lebar seperti Haggle (Haggle: 200/700=29%). Ini kunci
    yang dulu salah: -200 + energi BESAR (2400) -> spread 8% -> cascade serempak.
  - transmitEnergy & transmitRange DIKALIBRASI ke densitas kontak Helsinki
    (200 node movement >> 41 node trace Haggle). Beda energi antar-dataset itu
    WAJAR & defensible: "parameter energi dikalibrasi terhadap karakteristik
    mobilitas/densitas tiap dataset."

YANG DIVARIASIKAN (cari timing cascade 6-11h, sebagian node survive):
  - initialEnergy, transmitEnergy, transmitRange

OOM-SAFE: sequential per config (tiap config = 2 JVM EF+NoEF paralel).
  JANGAN jalankan script lain barengan.

Output: Z_helsinki_haggle/   CSV: summary_HG.csv
Estimasi: 6 config x ~45-60 min = ~5-6 jam (muat semalam).
  Catatan: msgTtl=2880 bikin pesan numpuk -> sim bisa agak lebih lambat & berat.

Usage:
    python run_helsinki_haggle.py --skip-compile   # kode Java sudah di-compile (revert -200)
    python run_helsinki_haggle.py                  # compile dulu (aman)
    python run_helsinki_haggle.py --resume         # lanjut yang belum jadi
"""

import argparse
import csv
import time
from datetime import datetime, timedelta
from pathlib import Path

import _sweep_lib as lib

OUT_DIR = lib.PROJECT_DIR / "Z_helsinki_haggle"
SUMMARY_CSV = OUT_DIR / "summary_HG.csv"
LOG_FILE = OUT_DIR / "run_HG.log"

# ============================================================================
# 6 CONFIG — copy param Q-learning Haggle, vary energy/TX/range
# ============================================================================
CONFIGS = [
    {
        "name": "HG1_pure_haggle_r10",
        "initialEnergy": 700, "transmitEnergy": 7.0, "transmitRange": 10,
        "rationale": "IDENTIK Haggle penuh (E700,TX7,range10). Uji konsistensi total. "
                     "Range 10 turunkan densitas -> mungkin mirip Haggle.",
    },
    {
        "name": "HG2_r10_E1000",
        "initialEnergy": 1000, "transmitEnergy": 5.0, "transmitRange": 10,
        "rationale": "Range 10 (sparse ala Haggle) + energi sedikit naik untuk Helsinki",
    },
    {
        "name": "HG3_r25_E1000",
        "initialEnergy": 1000, "transmitEnergy": 4.0, "transmitRange": 25,
        "rationale": "Range 25 (Helsinki native) + E1000/TX4 (= FINAL Helsinki lama)",
    },
    {
        "name": "HG4_r25_E1200",
        "initialEnergy": 1200, "transmitEnergy": 3.0, "transmitRange": 25,
        "rationale": "Range 25 + drain moderat -> target cascade ~6-8h",
    },
    {
        "name": "HG5_r25_E1500",
        "initialEnergy": 1500, "transmitEnergy": 2.5, "transmitRange": 25,
        "rationale": "Range 25 + gentle -> target cascade ~9-11h, sebagian survive",
    },
    {
        "name": "HG6_r25_E1300_TX3.5",
        "initialEnergy": 1300, "transmitEnergy": 3.5, "transmitRange": 25,
        "rationale": "Range 25 + titik tengah -> kandidat sweet spot cascade ~7-9h",
    },
]

# ============================================================================
# CONFIG TEMPLATE — Helsinki structure + Haggle Q-learning params
# ============================================================================

def build_config_hg(params, iter_name, router_type, report_dir):
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

    E = params["initialEnergy"]
    TX = params["transmitEnergy"]
    rng = params["transmitRange"]

    return f"""## Scenario settings (Helsinki structure + Haggle Q-learning params)
Scenario.name = {scenario_name}
Scenario.simulateConnections = true
Scenario.updateInterval = 0.1
Scenario.endTime = 43200

## Interface — transmitRange dikalibrasi (Haggle=10, Helsinki native=25)
btInterface.type = SimpleBroadcastInterface
btInterface.transmitSpeed = 250k
btInterface.transmitRange = {rng}
btInterface.scanInterval = 120

## Group settings (Helsinki 6 grup — dataset-specific, tidak diubah)
Scenario.nrofHostGroups = 6

Group.movementModel = ShortestPathMapBasedMovement
Group.router = {router_class}
Group.bufferSize = 50M
Group.waitTime = 0, 120
Group.nrofInterfaces = 1
Group.interface1 = btInterface
Group.speed = 0.5, 1.5
Group.msgTtl = 2880

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

# === Q-LEARNING PARAMS — COPY PERSIS DARI HAGGLE (konsistensi algoritma) ===
{ns}.updateInterval = 300
{ns}.baseDiscountGamma = 0.6
{ns}.learningCoeff = 0.8
{ns}.encounterDecayGamma = 0.9999
{ns}.qAgingOmega = 0.9999
{ns}.epsilonStart = 1.0
{ns}.epsilonEnd = 0.05
{ns}.epsilonDecayType = exponential
{ns}.simulationTotalTime = 43200

# === ENERGY — homogen (-200 di kode), dikalibrasi ke densitas Helsinki ===
{ns}.initialEnergy = {E}
{ns}.scanEnergy = 0.1
{ns}.transmitEnergy = {TX}
{ns}.receiveEnergy = 1.5

# === Message generation — msgTtl 2880 (ala Haggle: pesan numpuk, BF/EF aktif) ===
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
# RUNNER
# ============================================================================

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-compile", action="store_true")
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    log = lib.make_logger(LOG_FILE)

    log(f"\n{'#'*72}")
    log(f"# HELSINKI ALA-HAGGLE — 6 config (Q-params copy Haggle, energy dikalibrasi)")
    log(f"# Goal: Helsinki EF unggul (survival + cascade-longevity + delivery)")
    log(f"# Started: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    log(f"# Output : {OUT_DIR}")
    log(f"# Mode   : {'RESUME' if args.resume else 'FRESH'}, "
        f"{'NO_COMPILE' if args.skip_compile else 'WILL_COMPILE'}")
    log(f"{'#'*72}\n")

    if not args.skip_compile:
        lib.compile_java(log)

    if not SUMMARY_CSV.exists():
        with open(SUMMARY_CSV, "w", newline="", encoding="utf-8") as f:
            csv.writer(f).writerow(lib.CSV_HEADER)

    total_start = time.time()
    for idx, config in enumerate(CONFIGS, start=1):
        _process(config, idx, len(CONFIGS), log, args.resume)
        elapsed = time.time() - total_start
        remaining = (elapsed / idx) * (len(CONFIGS) - idx)
        eta = datetime.now() + timedelta(seconds=remaining)
        log(f"  ETA: {timedelta(seconds=int(remaining))} (finish ~ {eta.strftime('%H:%M:%S')})")

    total_min = (time.time() - total_start) / 60.0
    log(f"\n{'#'*72}")
    log(f"# SELESAI 6 config dalam {total_min:.1f} menit")
    log(f"# CSV: {SUMMARY_CSV}")
    log(f"{'#'*72}")
    _recommend(log)


def _process(config, idx, total, log, resume):
    name = config["name"]
    iter_dir = OUT_DIR / name
    iter_dir.mkdir(parents=True, exist_ok=True)

    log(f"\n{'='*72}")
    log(f"CONFIG {idx}/{total}: {name}")
    log(f"  E={config['initialEnergy']}, TX={config['transmitEnergy']}, "
        f"range={config['transmitRange']}  | Q-params = COPY Haggle")
    log(f"  {config['rationale']}")
    log(f"{'='*72}")

    ef_dir = iter_dir / "withEF"
    noef_dir = iter_dir / "withoutEF"
    ef_dir.mkdir(exist_ok=True)
    noef_dir.mkdir(exist_ok=True)

    if resume:
        ef_done = lib.find_report(ef_dir, f"{name}_WithEF", "NodeDeathReport")
        noef_done = lib.find_report(noef_dir, f"{name}_WithoutEF", "NodeDeathReport")
        if ef_done and noef_done:
            log("  [SKIP] sudah ada hasil")
            return

    ef_cfg = iter_dir / f"{name}_WithEF.txt"
    noef_cfg = iter_dir / f"{name}_WithoutEF.txt"
    ef_cfg.write_text(build_config_hg(config, name, "withEF", ef_dir.as_posix()), encoding="utf-8")
    noef_cfg.write_text(build_config_hg(config, name, "withoutEF", noef_dir.as_posix()), encoding="utf-8")

    log("  Run WithEF + WithoutEF PARALEL (-b 1)...")
    start = time.time()
    p1 = lib.run_simulation_async(ef_cfg, iter_dir / "withEF_sim.log")
    p2 = lib.run_simulation_async(noef_cfg, iter_dir / "withoutEF_sim.log")
    rc = lib.wait_for([p1, p2], ["EF", "NoEF"])
    dur = (time.time() - start) / 60.0
    log(f"  Selesai {dur:.1f} min. RC: EF={rc['EF']}, NoEF={rc['NoEF']}")

    base = [name, "HG", config["initialEnergy"], "1.0",
            config["transmitEnergy"], 1.5, ""]

    if rc["EF"] != 0 or rc["NoEF"] != 0:
        log("  [ERROR] simulasi gagal (cek sim log; mungkin OOM jika ada proses lain)")
        _row(base + [""]*17 + [f"{dur:.1f}", config["rationale"], "SIM_FAILED"])
        return

    ef_deaths = lib.parse_node_deaths(lib.find_report(ef_dir, f"{name}_WithEF", "NodeDeathReport"))
    noef_deaths = lib.parse_node_deaths(lib.find_report(noef_dir, f"{name}_WithoutEF", "NodeDeathReport"))
    ef_stats = lib.parse_message_stats(lib.find_report(ef_dir, f"{name}_WithEF", "MessageStatsReport")) or {}
    noef_stats = lib.parse_message_stats(lib.find_report(noef_dir, f"{name}_WithoutEF", "MessageStatsReport")) or {}

    if ef_deaths is None or noef_deaths is None:
        log("  [ERROR] parse gagal")
        _row(base + [""]*17 + [f"{dur:.1f}", config["rationale"], "PARSE_FAILED"])
        return

    r = lib.evaluate(ef_deaths, noef_deaths, ef_stats, noef_stats)
    log(f"  RESULTS:")
    log(f"    Deaths   : EF={r['n_ef']}, NoEF={r['n_noef']}  (survival {r['survival_diff']:+d})")
    log(f"    Cascade  : EF t50={r['ef_t50_h']:.2f}h, NoEF t50={r['noef_t50_h']:.2f}h "
        f"(gap {r['cascade_gap_h']:+.2f}h)")
    log(f"    1st death: EF={r['ef_first_h']:.2f}h, NoEF={r['noef_first_h']:.2f}h")
    log(f"    Delivery : EF={r['ef_del']:.4f}, NoEF={r['noef_del']:.4f} ({r['delivery_diff_pct']:+.2f}%)")
    log(f"    Overhead : EF={ef_stats.get('overhead_ratio',0):.2f}, NoEF={noef_stats.get('overhead_ratio',0):.2f}")
    log(f"    >>> 3-KEY: {r['three_key']}/3   VERDICT: {r['verdict']}")

    _row(base + [
        r["n_ef"], r["n_noef"], r["survival_diff"],
        f"{r['ef_t50_h']:.2f}", f"{r['noef_t50_h']:.2f}", f"{r['cascade_gap_h']:.2f}",
        f"{r['ef_first_h']:.2f}", f"{r['noef_first_h']:.2f}",
        f"{r['ef_del']:.4f}", f"{r['noef_del']:.4f}", f"{r['delivery_diff_pct']:.2f}",
        ef_stats.get("latency_avg", ""), noef_stats.get("latency_avg", ""),
        ef_stats.get("overhead_ratio", ""), noef_stats.get("overhead_ratio", ""),
        ef_stats.get("hopcount_avg", ""), noef_stats.get("hopcount_avg", ""),
        r["three_key"], f"{dur:.1f}", config["rationale"], r["verdict"],
    ])


def _row(row):
    with open(SUMMARY_CSV, "a", newline="", encoding="utf-8") as f:
        csv.writer(f).writerow(row)


def _recommend(log):
    if not SUMMARY_CSV.exists():
        return
    rows = []
    with open(SUMMARY_CSV, "r", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            try:
                row["_3key"] = int(row["three_key_count"]) if row["three_key_count"] else 0
                row["_surv"] = int(row["survival_diff"]) if row["survival_diff"] else -999
                row["_deliv"] = float(row["delivery_diff_pct"]) if row["delivery_diff_pct"] else -999
                row["_casc"] = float(row["cascade_gap_h"]) if row["cascade_gap_h"] else -999
                row["_deaths"] = int(row["withEF_deaths"]) if row["withEF_deaths"] else -1
                rows.append(row)
            except (ValueError, TypeError):
                continue
    if not rows:
        return
    valid = [r for r in rows if 20 <= r["_deaths"] <= 190]
    pool = valid if valid else rows
    three = [r for r in pool if r["_3key"] == 3]

    log(f"\n{'='*72}")
    if three:
        best = max(three, key=lambda r: (r["_surv"], r["_deliv"], r["_casc"]))
        log(f"  🏆 ADA {len(three)} config 3-KEY WIN! Terbaik: {best['iter_name']}")
    else:
        best = max(pool, key=lambda r: (r["_3key"], r["_surv"], r["_deliv"]))
        log(f"  Tidak ada 3-key penuh. Terbaik (2/3): {best['iter_name']}")
    log(f"     E={best['initialEnergy']}, TX={best['transmitEnergy']}")
    log(f"     survival {best['_surv']:+d} | cascade-gap {best['_casc']:+.2f}h | "
        f"delivery {best['_deliv']:+.2f}% | 3-key {best['_3key']}/3")
    log(f"     verdict: {best['verdict']}")
    log(f"{'='*72}\n")
    log(f"  Tabel ringkas:")
    log(f"  {'config':<24} {'surv':>5} {'casc':>6} {'deliv%':>7} {'3key':>5}")
    for r in sorted(rows, key=lambda r: (r["_3key"], r["_surv"]), reverse=True):
        log(f"  {r['iter_name']:<24} {r['_surv']:>+5d} {r['_casc']:>+6.2f} "
            f"{r['_deliv']:>+7.2f} {r['_3key']:>4}/3")


if __name__ == "__main__":
    main()
