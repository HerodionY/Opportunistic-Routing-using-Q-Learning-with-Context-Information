"""
runner.py
=========
Runner independen untuk masing-masing mode simulasi.
Jalankan di 4 terminal berbeda secara bersamaan:

    python runner.py haggle_with_ef
    python runner.py haggle_without_ef
    python runner.py helsinki_with_ef
    python runner.py helsinki_without_ef

Setiap mode membaca params dari run_params.json (field "haggle" atau "helsinki").
Sesama haggle pakai params yang sama, sesama helsinki pakai params yang sama (fair/apple-to-apple).

Hasil disimpan di:
    multiRun/iter_N/haggle_with_ef/
    multiRun/iter_N/haggle_without_ef/
    multiRun/iter_N/helsinki_with_ef/
    multiRun/iter_N/helsinki_without_ef/
"""

import json
import os
import re
import sys
import subprocess
import time
from datetime import datetime

# ─── Argumen mode ─────────────────────────────────────────────────────────────
VALID_MODES = (
    "haggle_with_ef",
    "haggle_without_ef",
    "helsinki_with_ef",
    "helsinki_without_ef",
)

if len(sys.argv) < 2 or sys.argv[1] not in VALID_MODES:
    print("Penggunaan:")
    for m in VALID_MODES:
        print(f"    python runner.py {m}")
    sys.exit(1)

MODE = sys.argv[1]

# ─── Config per mode ──────────────────────────────────────────────────────────
#
#  base          : file config dasar (tidak dimodifikasi, hanya dibaca)
#  ns            : namespace energy params di dalam config (CCRoutingExpert atau CCRouting)
#  dataset       : key di run_params.json untuk mengambil params ("haggle" / "helsinki")
#  convert_router: True  -> ganti CCRouting -> CCRoutingExpert di seluruh config
#                  False -> tidak perlu konversi, router sudah benar
#
CONFIG_SETTINGS = {
    "haggle_with_ef": {
        "base":           "Bench_CCRouting_Haggle.txt",
        "ns":             "CCRoutingExpert",
        "dataset":        "haggle",
        "convert_router": True,   # base pakai CCRouting, konversi ke CCRoutingExpert
    },
    "haggle_without_ef": {
        "base":           "CC1_Haggle_without_energy.txt",
        "ns":             "CCRouting",
        "dataset":        "haggle",
        "convert_router": False,  # sudah pakai CCRoutingWithoutEnergyContext + ns CCRouting
    },
    "helsinki_with_ef": {
        "base":           "Bench_CCRouting_Helsinki_v4.txt",
        "ns":             "CCRoutingExpert",
        "dataset":        "helsinki",
        "convert_router": False,  # sudah pakai CCRoutingExpert + ns CCRoutingExpert
    },
    "helsinki_without_ef": {
        "base":           "Bench_CCRoutingWithoutEnergy_Helsinki_v4.txt",
        "ns":             "CCRouting",
        "dataset":        "helsinki",
        "convert_router": False,  # sudah pakai CCRoutingWithoutEnergyContext + ns CCRouting
    },
}

cfg         = CONFIG_SETTINGS[MODE]
BASE_CONFIG = cfg["base"]
NS          = cfg["ns"]       # namespace untuk energy params di config
DATASET     = cfg["dataset"]  # key params dari run_params.json

# ─── File tracking progress per mode (independen) ─────────────────────────────
PARAMS_FILE   = "run_params.json"
PROGRESS_FILE = f"_progress_{MODE}.json"

JAVA = r'C:\Program Files\Common Files\Oracle\Java\javapath\java.exe'


# ──────────────────────────────────────────────────────────────────────────────
# UTILITIES
# ──────────────────────────────────────────────────────────────────────────────

def load_params():
    if not os.path.exists(PARAMS_FILE):
        print(f"✗ File {PARAMS_FILE} tidak ditemukan!")
        print(f"  Jalankan dulu: python generate_params.py")
        sys.exit(1)
    with open(PARAMS_FILE) as f:
        return json.load(f)


def load_progress():
    if os.path.exists(PROGRESS_FILE):
        with open(PROGRESS_FILE) as f:
            return json.load(f)
    return {"current_iter": 1}


def save_progress(progress):
    with open(PROGRESS_FILE, "w") as f:
        json.dump(progress, f)


def get_iter_params(all_params, iter_num):
    """
    Ambil params untuk iterasi ini.
    Support dua format run_params.json:
      - Format baru (generate_params.py hasil rewrite):
        [{"iteration": N, "haggle": {...}, "helsinki": {...}}, ...]
      - Format lama / custom flat:
        [{"initialEnergy": ..., "scanEnergy": ..., ...}, ...]
    """
    entry = all_params[iter_num - 1]
    if DATASET in entry:
        return entry[DATASET]
    # fallback: flat dict, pakai langsung
    return entry


def modify_config(params, iter_num):
    """
    Baca base config, lakukan:
      1. (Opsional) Convert CCRouting -> CCRoutingExpert jika convert_router=True
      2. Ganti energy params sesuai namespace (NS)
      3. Ganti reportDir dan Scenario.name
    Simpan ke file temp.
    """
    with open(BASE_CONFIG, encoding="utf-8") as f:
        content = f.read()

    report_dir = f"multiRun/iter_{iter_num}/{MODE}/"

    # ── 1. Convert router namespace (hanya haggle_with_ef) ────────────────────
    if cfg["convert_router"]:
        # Ganti deklarasi router
        content = re.sub(
            r'(Group\.router\s*=\s*)CCRouting\b',
            r'\1CCRoutingExpert',
            content
        )
        # Ganti SEMUA CCRouting.xxx -> CCRoutingExpert.xxx
        # (mencakup updateInterval, baseDiscountGamma, initialEnergy, dll.)
        content = re.sub(r'\bCCRouting\.', 'CCRoutingExpert.', content)

    # ── 2. Ganti energy params sesuai namespace ───────────────────────────────
    content = re.sub(
        rf'{re.escape(NS)}\.initialEnergy\s*=.*',
        f'{NS}.initialEnergy = {int(params["initialEnergy"])}',
        content
    )
    content = re.sub(
        rf'{re.escape(NS)}\.scanEnergy\s*=.*',
        f'{NS}.scanEnergy = {params["scanEnergy"]}',
        content
    )
    content = re.sub(
        rf'{re.escape(NS)}\.transmitEnergy\s*=.*',
        f'{NS}.transmitEnergy = {params["transmitEnergy"]}',
        content
    )
    content = re.sub(
        rf'{re.escape(NS)}\.receiveEnergy\s*=.*',
        f'{NS}.receiveEnergy = {params["receiveEnergy"]}',
        content
    )

    # ── 3. Ganti reportDir dan Scenario.name ─────────────────────────────────
    content = re.sub(
        r'Report\.reportDir\s*=.*',
        f'Report.reportDir = {report_dir}',
        content
    )
    content = re.sub(
        r'Scenario\.name\s*=.*',
        f'Scenario.name = {MODE}_iter_{iter_num}',
        content
    )

    # ── Simpan ke file temp ───────────────────────────────────────────────────
    temp_config = f"_temp_{MODE}_iter_{iter_num}.txt"
    with open(temp_config, "w", encoding="utf-8") as f:
        f.write(content)

    return temp_config, report_dir


def run_simulation(config_file):
    """Jalankan ONE simulator."""
    cmd = [
        JAVA,
        '-Xmx12G',
        '-cp', 'target;lib/ECLA.jar;lib/DTNConsoleConnection.jar;lib/lombok.jar',
        'core.DTNSim',
        '-b', '1',
        config_file
    ]
    return subprocess.run(cmd, capture_output=True, text=True)


def print_separator():
    print("=" * 60)


# ──────────────────────────────────────────────────────────────────────────────
# MAIN LOOP
# ──────────────────────────────────────────────────────────────────────────────

def main():
    all_params = load_params()
    progress   = load_progress()
    total      = len(all_params)

    print_separator()
    print(f"  Runner : [{MODE.upper()}]")
    print(f"  Base   : {BASE_CONFIG}  (namespace: {NS})")
    print(f"  Dataset: {DATASET}")
    print(f"  Total iterasi : {total}")
    print(f"  Mulai dari    : iterasi {progress['current_iter']}")
    print_separator()

    while progress["current_iter"] <= total:
        iter_num = progress["current_iter"]
        params   = get_iter_params(all_params, iter_num)

        # ── Header iterasi ────────────────────────────────────────────────────
        now_str = datetime.now().strftime("%H:%M:%S")
        print(f"\n[{MODE}] Iterasi {iter_num}/{total}  —  {now_str}")
        print(f"  initialEnergy  = {int(params['initialEnergy'])}")
        print(f"  scanEnergy     = {params['scanEnergy']}")
        print(f"  transmitEnergy = {params['transmitEnergy']}")
        print(f"  receiveEnergy  = {params['receiveEnergy']}")

        # ── Buat folder & temp config ─────────────────────────────────────────
        temp_config, report_dir = modify_config(params, iter_num)
        os.makedirs(report_dir, exist_ok=True)

        # Simpan params iterasi ini ke folder hasil
        with open(f"{report_dir}params.json", "w") as f:
            json.dump(params, f, indent=2)

        # ── Jalankan simulasi ─────────────────────────────────────────────────
        print(f"  Menjalankan simulasi... (ini akan lama)")
        start_time = time.time()
        result     = run_simulation(temp_config)
        elapsed    = time.time() - start_time
        minutes    = elapsed / 60

        # ── Simpan log run ────────────────────────────────────────────────────
        with open(f"{report_dir}run_log.txt", "w", encoding="utf-8") as f:
            f.write(f"Mode      : {MODE}\n")
            f.write(f"Iterasi   : {iter_num}/{total}\n")
            f.write(f"Selesai   : {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
            f.write(f"Durasi    : {minutes:.1f} menit\n")
            f.write(f"Params    :\n{json.dumps(params, indent=4)}\n")
            f.write(f"\n--- STDOUT ---\n{result.stdout}")
            f.write(f"\n--- STDERR ---\n{result.stderr}")

        # ── Hapus temp config ─────────────────────────────────────────────────
        if os.path.exists(temp_config):
            os.remove(temp_config)

        # ── Ringkasan iterasi ─────────────────────────────────────────────────
        status = "✓ OK" if result.returncode == 0 else "✗ ERROR"
        print(f"  {status} — selesai dalam {minutes:.1f} menit")
        print(f"  Hasil → {report_dir}")
        if result.returncode != 0:
            print(f"  [!] Ada error, cek {report_dir}run_log.txt")

        # ── Update progress & lanjut ──────────────────────────────────────────
        progress["current_iter"] = iter_num + 1
        save_progress(progress)

    # ── Semua iterasi selesai ─────────────────────────────────────────────────
    print_separator()
    print(f"[{MODE}] SEMUA {total} ITERASI SELESAI!")
    print(f"  Hasil ada di folder multiRun/iter_*/{MODE}/")
    print_separator()


if __name__ == "__main__":
    main()
