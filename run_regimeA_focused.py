"""
REGIME A FOKUS — eksplorasi sekitar A2 (pemenang terbaik sejauh ini).

A2 (E2400, eps0.30, TX2.5, RX0.8) memberi survival +4 DAN delivery +0.10%
(satu-satunya dari 25 sim yang dua-duanya positif). Sweep ini mengelilingi A2
untuk cari margin lebih besar di regime gentle-drain (regime yang terbukti
RAMAH-DELIVERY: A1/A2/A5 semua delivery EF >= NoEF).

Arah eksplorasi (berdasar data):
  - epsilon TURUN (0.30 -> 0.20): EF lebih dipakai. A2 eps0.30 (+4) jauh lebih
    baik dari A1 eps0.40 (-4) -> eps rendah bantu survival.
  - energy NAIK (2400 -> 2600): A2 E2400 (+4) > A5 E2300 (-1) -> energy bantu
    survival.
  - termasuk RE-RUN A3 & A4 yang kemarin GAGAL karena OOM (bukan config jelek).

PENTING — OOM SAFETY:
  Script ini SEQUENTIAL (1 config pada satu waktu, masing-masing 2 JVM:
  WithEF + WithoutEF paralel). JANGAN jalankan barengan script lain -> kemarin
  4 JVM sekaligus = 48GB = OOM. Cukup script INI saja.

Output: Z_regimeA/ (folder BARU, terpisah). CSV: summary_regimeA.csv
Estimasi: ~6 config x ~60 menit = ~6 jam (muat 1 malam).

Usage:
    python run_regimeA_focused.py --skip-compile     # tidak ada perubahan kode Java
    python run_regimeA_focused.py                    # kalau mau compile dulu (aman)
    python run_regimeA_focused.py --resume           # lanjut yang belum jadi
"""

import argparse
import _sweep_lib as lib

CONFIGS = [
    {
        "name": "RA1_E2400_eps25",
        "regime": "A_focused",
        "initialEnergy": 2400, "epsilonStart": 0.25,
        "transmitEnergy": 2.5, "receiveEnergy": 0.8,
        "target_first_death_h": "6-7",
        "rationale": "Re-run A3 (OOM). Sama A2 tapi EF lebih kuat (eps0.25) -> cek survival naik",
    },
    {
        "name": "RA2_E2400_eps20",
        "regime": "A_focused",
        "initialEnergy": 2400, "epsilonStart": 0.20,
        "transmitEnergy": 2.5, "receiveEnergy": 0.8,
        "target_first_death_h": "6-7",
        "rationale": "EF terkuat di energy A2 -> EF paling dominan, harap survival maksimal",
    },
    {
        "name": "RA3_E2500_eps30",
        "regime": "A_focused",
        "initialEnergy": 2500, "epsilonStart": 0.30,
        "transmitEnergy": 2.5, "receiveEnergy": 0.8,
        "target_first_death_h": "7-8",
        "rationale": "Energy naik dari A2 (eps sama 0.30) -> buffer survival lebih besar",
    },
    {
        "name": "RA4_E2500_eps25",
        "regime": "A_focused",
        "initialEnergy": 2500, "epsilonStart": 0.25,
        "transmitEnergy": 2.5, "receiveEnergy": 0.8,
        "target_first_death_h": "7-8",
        "rationale": "Kombinasi energy naik + EF kuat -> kandidat terbaik regime A",
    },
    {
        "name": "RA5_E2600_eps28",
        "regime": "A_focused",
        "initialEnergy": 2600, "epsilonStart": 0.28,
        "transmitEnergy": 2.5, "receiveEnergy": 0.8,
        "target_first_death_h": "7-8",
        "rationale": "Energy tertinggi + EF agak kuat -> survivor terbanyak, kurva paling landai",
    },
    {
        "name": "RA6_E2500_eps35_TX2",
        "regime": "A_focused",
        "initialEnergy": 2500, "epsilonStart": 0.35,
        "transmitEnergy": 2.0, "receiveEnergy": 0.6,
        "target_first_death_h": "8-9",
        "rationale": "Re-run A4 area (OOM). Drain paling pelan (TX2.0) -> cascade paling bertahap",
    },
]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--skip-compile", action="store_true")
    p.add_argument("--resume", action="store_true")
    args = p.parse_args()
    lib.run_sweep(CONFIGS, suffix="regimeA",
                  skip_compile=args.skip_compile, resume=args.resume,
                  out_dir=lib.PROJECT_DIR / "Z_regimeA")


if __name__ == "__main__":
    main()
