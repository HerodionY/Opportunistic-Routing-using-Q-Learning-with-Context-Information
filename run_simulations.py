#!/usr/bin/env python3
"""
run_simulations.py
Menjalankan ke-9 simulasi The ONE secara sekuensial dengan progress tracking.

Usage:
    python run_simulations.py
    python run_simulations.py --jar one.jar
    python run_simulations.py --skip-orqlci        # hanya jalankan baseline baru
    python run_simulations.py --only Helsinki       # hanya dataset tertentu
"""

import subprocess
import sys
import time
import argparse
import os
from datetime import datetime, timedelta

# ─────────────────────────────────────────────
# Daftar simulasi: (label, config_file, dataset)
# ─────────────────────────────────────────────
SIMULATIONS = [
    # ORQLCI (sudah ada hasilnya, tapi bisa di-rerun)
    ("ORQLCI",    "Helsinki", "Bench_CCRouting.txt"),
    ("ORQLCI",    "Haggle",   "Bench_CCRouting_Haggle.txt"),
    ("ORQLCI",    "Reality",  "Bench_CCRouting_Reality.txt"),
    # Epidemic baseline
    ("Epidemic",  "Helsinki", "Bench_Epidemic_Helsinki.txt"),
    ("Epidemic",  "Haggle",   "Bench_Epidemic_Haggle.txt"),
    ("Epidemic",  "Reality",  "Bench_Epidemic_Reality.txt"),
    # Prophet baseline
    ("Prophet",   "Helsinki", "Bench_Prophet_Helsinki.txt"),
    ("Prophet",   "Haggle",   "Bench_Prophet_Haggle.txt"),
    ("Prophet",   "Reality",  "Bench_Prophet_Reality.txt"),
]

# Estimasi durasi tiap simulasi (menit) — untuk ETA kasar
DURATION_ESTIMATE = {
    "Helsinki": 5,
    "Haggle":   10,
    "Reality":  30,
}


def fmt_duration(seconds):
    return str(timedelta(seconds=int(seconds)))


def run_one(jar_path, config_file, label, dataset, idx, total, log_dir):
    """Jalankan satu simulasi, return (success, elapsed_seconds, log_path)."""
    log_filename = os.path.join(log_dir, f"{label}_{dataset}.log")
    cmd = ["java", "-jar", jar_path, "-b", "1", config_file]

    print(f"\n{'='*60}")
    print(f"[{idx}/{total}] {label} — {dataset}")
    print(f"  Config : {config_file}")
    print(f"  Log    : {log_filename}")
    print(f"  Mulai  : {datetime.now().strftime('%H:%M:%S')}")
    print(f"{'='*60}")

    start = time.time()
    try:
        with open(log_filename, "w") as logf:
            proc = subprocess.run(
                cmd,
                stdout=logf,
                stderr=subprocess.STDOUT,
                text=True,
            )
        elapsed = time.time() - start
        success = proc.returncode == 0

        status = "✅ SELESAI" if success else f"❌ GAGAL (exit {proc.returncode})"
        print(f"  Status : {status}")
        print(f"  Durasi : {fmt_duration(elapsed)}")
        return success, elapsed, log_filename

    except FileNotFoundError:
        elapsed = time.time() - start
        print(f"  ❌ ERROR: '{jar_path}' tidak ditemukan.")
        print(f"     Pastikan path ke one.jar sudah benar (gunakan --jar <path>).")
        return False, elapsed, log_filename

    except KeyboardInterrupt:
        print("\n  ⚠️  Simulasi dihentikan oleh user (Ctrl+C).")
        raise


def main():
    parser = argparse.ArgumentParser(description="Runner untuk 9 simulasi The ONE")
    parser.add_argument("--jar", default="one.jar", help="Path ke one.jar (default: one.jar)")
    parser.add_argument("--skip-orqlci", action="store_true", help="Lewati simulasi ORQLCI (hanya jalankan baseline)")
    parser.add_argument("--only", choices=["Helsinki", "Haggle", "Reality"], help="Hanya jalankan dataset tertentu")
    parser.add_argument("--log-dir", default="sim_logs", help="Folder untuk menyimpan log (default: sim_logs)")
    args = parser.parse_args()

    # Filter simulasi sesuai argumen
    sims = SIMULATIONS
    if args.skip_orqlci:
        sims = [(r, d, c) for r, d, c in sims if r != "ORQLCI"]
    if args.only:
        sims = [(r, d, c) for r, d, c in sims if d == args.only]

    if not sims:
        print("Tidak ada simulasi yang cocok dengan filter yang diberikan.")
        sys.exit(1)

    # Buat folder log
    os.makedirs(args.log_dir, exist_ok=True)

    total = len(sims)
    est_total = sum(DURATION_ESTIMATE.get(d, 15) for _, d, _ in sims)

    print(f"\n{'#'*60}")
    print(f"  The ONE Simulator — Batch Runner")
    print(f"  Simulasi  : {total}")
    print(f"  JAR       : {args.jar}")
    print(f"  Log dir   : {args.log_dir}/")
    print(f"  Est. total: ~{est_total} menit")
    print(f"  Mulai     : {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"{'#'*60}")

    results = []
    total_start = time.time()

    for idx, (router, dataset, config) in enumerate(sims, start=1):
        # Cek config ada
        if not os.path.exists(config):
            print(f"\n⚠️  Config '{config}' tidak ditemukan, dilewati.")
            results.append((router, dataset, config, False, 0, None))
            continue

        try:
            success, elapsed, log_path = run_one(
                args.jar, config, router, dataset, idx, total, args.log_dir
            )
            results.append((router, dataset, config, success, elapsed, log_path))
        except KeyboardInterrupt:
            print("\n\n⚠️  Batch dihentikan. Hasil parsial ditampilkan di bawah.")
            break

    # ─── Ringkasan ───
    total_elapsed = time.time() - total_start
    print(f"\n\n{'#'*60}")
    print(f"  RINGKASAN HASIL")
    print(f"{'#'*60}")
    print(f"  {'Router':<12} {'Dataset':<10} {'Status':<10} {'Durasi'}")
    print(f"  {'-'*50}")
    for router, dataset, config, success, elapsed, _ in results:
        status = "✅ OK" if success else "❌ GAGAL"
        print(f"  {router:<12} {dataset:<10} {status:<10} {fmt_duration(elapsed)}")

    n_ok   = sum(1 for *_, s, _, __ in results if s)
    n_fail = len(results) - n_ok
    print(f"\n  Selesai  : {n_ok}/{len(results)} sukses, {n_fail} gagal")
    print(f"  Total    : {fmt_duration(total_elapsed)}")
    print(f"  Selesai  : {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}")

    if n_fail > 0:
        print(f"\n  ⚠️  Cek log di '{args.log_dir}/' untuk detail error.")
        sys.exit(1)


if __name__ == "__main__":
    main()
