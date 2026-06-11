"""
SATU RUN FINAL TERKALKULASI — E1400/TX2.5 (interpolasi HG5).

Desain: ambil HG5 (E1500/TX2.5/range25/ttl2880/Q-param Haggle) yang memberi
delivery EF menang (+3.76%) tapi cuma 27 mati (cascade datar), lalu UBAH HANYA
initialEnergy 1500->1400. Target: ~50-90 kematian (cascade terlihat di chart)
sambil mempertahankan delivery EF unggul.

Semua param lain IDENTIK HG5 -> perubahan hasil murni dari energi (1 variabel).

OOM-SAFE: 2 JVM saja (EF + NoEF paralel). Jangan run script lain barengan.
Output: Z_helsinki_final/   CSV: summary_final.csv
Estimasi: ~30-60 menit (tergantung berapa cepat cascade).

Usage:
    python run_helsinki_final.py --skip-compile
"""

import argparse
import csv
import time
from datetime import datetime
from pathlib import Path

import _sweep_lib as lib
from run_helsinki_haggle import build_config_hg

OUT_DIR = lib.PROJECT_DIR / "Z_helsinki_final"
SUMMARY_CSV = OUT_DIR / "summary_final.csv"
LOG_FILE = OUT_DIR / "run_final.log"

CONFIG = {
    "name": "FINAL_E1400_TX2.5",
    "initialEnergy": 1400, "transmitEnergy": 2.5, "transmitRange": 25,
    "rationale": "Interpolasi HG5 (E1500->1400, sisanya identik). Target ~50-90 mati + delivery EF menang.",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-compile", action="store_true")
    args = parser.parse_args()

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    log = lib.make_logger(LOG_FILE)

    log(f"\n{'#'*72}")
    log(f"# HELSINKI FINAL — 1 run terkalkulasi: {CONFIG['name']}")
    log(f"# E={CONFIG['initialEnergy']}, TX={CONFIG['transmitEnergy']}, range={CONFIG['transmitRange']}")
    log(f"# Param lain identik HG5. Q-params copy Haggle. msgTtl=2880.")
    log(f"# Started: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    log(f"{'#'*72}\n")

    if not args.skip_compile:
        lib.compile_java(log)

    if not SUMMARY_CSV.exists():
        with open(SUMMARY_CSV, "w", newline="", encoding="utf-8") as f:
            csv.writer(f).writerow(lib.CSV_HEADER)

    name = CONFIG["name"]
    iter_dir = OUT_DIR / name
    iter_dir.mkdir(parents=True, exist_ok=True)
    ef_dir = iter_dir / "withEF"
    noef_dir = iter_dir / "withoutEF"
    ef_dir.mkdir(exist_ok=True)
    noef_dir.mkdir(exist_ok=True)

    ef_cfg = iter_dir / f"{name}_WithEF.txt"
    noef_cfg = iter_dir / f"{name}_WithoutEF.txt"
    ef_cfg.write_text(build_config_hg(CONFIG, name, "withEF", ef_dir.as_posix()), encoding="utf-8")
    noef_cfg.write_text(build_config_hg(CONFIG, name, "withoutEF", noef_dir.as_posix()), encoding="utf-8")

    log("Run WithEF + WithoutEF PARALEL (-b 1)...")
    start = time.time()
    p1 = lib.run_simulation_async(ef_cfg, iter_dir / "withEF_sim.log")
    p2 = lib.run_simulation_async(noef_cfg, iter_dir / "withoutEF_sim.log")
    rc = lib.wait_for([p1, p2], ["EF", "NoEF"])
    dur = (time.time() - start) / 60.0
    log(f"Selesai {dur:.1f} min. RC: EF={rc['EF']}, NoEF={rc['NoEF']}")

    if rc["EF"] != 0 or rc["NoEF"] != 0:
        log("[ERROR] simulasi gagal (cek sim log)")
        return

    ef_deaths = lib.parse_node_deaths(lib.find_report(ef_dir, f"{name}_WithEF", "NodeDeathReport"))
    noef_deaths = lib.parse_node_deaths(lib.find_report(noef_dir, f"{name}_WithoutEF", "NodeDeathReport"))
    ef_stats = lib.parse_message_stats(lib.find_report(ef_dir, f"{name}_WithEF", "MessageStatsReport")) or {}
    noef_stats = lib.parse_message_stats(lib.find_report(noef_dir, f"{name}_WithoutEF", "MessageStatsReport")) or {}

    if ef_deaths is None or noef_deaths is None:
        log("[ERROR] parse gagal")
        return

    r = lib.evaluate(ef_deaths, noef_deaths, ef_stats, noef_stats)

    log(f"\n{'='*72}")
    log(f"  HASIL {name}")
    log(f"{'='*72}")
    log(f"  Deaths   : EF={r['n_ef']}, NoEF={r['n_noef']}  (survival {r['survival_diff']:+d})")
    log(f"  Cascade  : EF t50={r['ef_t50_h']:.2f}h, NoEF t50={r['noef_t50_h']:.2f}h (gap {r['cascade_gap_h']:+.2f}h)")
    log(f"  1st death: EF={r['ef_first_h']:.2f}h, NoEF={r['noef_first_h']:.2f}h")
    log(f"  Delivery : EF={r['ef_del']:.4f}, NoEF={r['noef_del']:.4f} ({r['delivery_diff_pct']:+.2f}%)")
    log(f"  Latency  : EF={ef_stats.get('latency_avg',0):.1f}, NoEF={noef_stats.get('latency_avg',0):.1f}")
    log(f"  Overhead : EF={ef_stats.get('overhead_ratio',0):.2f}, NoEF={noef_stats.get('overhead_ratio',0):.2f}")
    log(f"  >>> 3-KEY: {r['three_key']}/3   VERDICT: {r['verdict']}")
    log(f"{'='*72}")

    with open(SUMMARY_CSV, "a", newline="", encoding="utf-8") as f:
        csv.writer(f).writerow([
            name, "FINAL", CONFIG["initialEnergy"], "1.0",
            CONFIG["transmitEnergy"], 1.5, "",
            r["n_ef"], r["n_noef"], r["survival_diff"],
            f"{r['ef_t50_h']:.2f}", f"{r['noef_t50_h']:.2f}", f"{r['cascade_gap_h']:.2f}",
            f"{r['ef_first_h']:.2f}", f"{r['noef_first_h']:.2f}",
            f"{r['ef_del']:.4f}", f"{r['noef_del']:.4f}", f"{r['delivery_diff_pct']:.2f}",
            ef_stats.get("latency_avg", ""), noef_stats.get("latency_avg", ""),
            ef_stats.get("overhead_ratio", ""), noef_stats.get("overhead_ratio", ""),
            ef_stats.get("hopcount_avg", ""), noef_stats.get("hopcount_avg", ""),
            r["three_key"], f"{dur:.1f}", CONFIG["rationale"], r["verdict"],
        ])

    # Verdict-aware guidance
    log("")
    if r["three_key"] == 3:
        log("  🎉 3-KEY WIN! Survival + cascade-longevity + delivery semua EF unggul.")
        log("     -> Ini config Helsinki final-mu. Lanjut bikin chart.")
    elif r["survival_diff"] > 0 and r["delivery_diff_pct"] > 0:
        log("  ✅ Survival + delivery dua-duanya EF unggul (cascade tie).")
        log("     -> Sudah cukup baik untuk chart Helsinki. Bandingkan dgn A2.")
    elif 40 <= r["n_ef"] <= 110:
        log(f"  📊 Cascade terlihat ({r['n_ef']} mati). Cek apakah survival/delivery cukup.")
    else:
        log("  ⚠️  Belum ideal. Bandingkan dengan A2 (survival +4, delivery +0.10%) & HG5.")
    log("")


if __name__ == "__main__":
    main()
