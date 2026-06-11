"""
REGIME B — HIGH-ENERGY / LATE-CASCADE (margin TIPIS tapi RELIABLE).

Centered di sekitar PEMENANG TERBUKTI dari run kemarin:
    iter10 (E3500, eps0.20, TX4.0): survival +4, delivery +0.0063, cascade tie
    iter4  (E4000, eps0.40, TX4.0): survival +3, delivery +0.0014, cascade +
Keduanya SUDAH menang 3-key (cuma marginnya tipis).

Grid: energy {3300, 3500, 3600, 3700} x epsilon {0.20-0.30}, bias eps RENDAH
(eps rendah = EF lebih dipakai = delivery advantage lebih besar; terbukti iter10
eps0.20 > iter4 eps0.40 dalam delivery). Energy diturunkan dari 4000-4500 ke
3300-3700 supaya cukup kematian -> survival margin sedikit lebih besar (iter10
E3500 kasih +4, lebih baik dari iter4 E4000 +3).

Semua distinct dari iter4/iter10 dan satu sama lain (seed fixed -> param identik =
hasil identik, jadi tidak ada run mubazir).

Ini HALF yang AMAN: probabilitas tinggi dapat 3-key win, walau margin moderat.

Jalankan BARENGAN dengan run_5_combined_A.py di terminal terpisah.

Usage:
    python run_5_combined_B.py
    python run_5_combined_B.py --skip-compile
    python run_5_combined_B.py --resume
"""

import argparse
import _sweep_lib as lib

CONFIGS_B = [
    {
        "name": "B1_E3300_eps20",
        "regime": "B_high_late",
        "initialEnergy": 3300, "epsilonStart": 0.20,
        "transmitEnergy": 4.0, "receiveEnergy": 1.5,
        "target_first_death_h": "~9.5",
        "rationale": "Energy sedikit di bawah iter10 -> lebih banyak mati -> survival margin lebih besar",
    },
    {
        "name": "B2_E3500_eps25",
        "regime": "B_high_late",
        "initialEnergy": 3500, "epsilonStart": 0.25,
        "transmitEnergy": 4.0, "receiveEnergy": 1.5,
        "target_first_death_h": "~10",
        "rationale": "Energy iter10 winner + eps sedikit naik, cek sensitivitas delivery",
    },
    {
        "name": "B3_E3700_eps20",
        "regime": "B_high_late",
        "initialEnergy": 3700, "epsilonStart": 0.20,
        "transmitEnergy": 4.0, "receiveEnergy": 1.5,
        "target_first_death_h": "~10.3",
        "rationale": "Energy sedikit di atas iter10 + EF terkuat (eps0.20), kurva lebih landai",
    },
    {
        "name": "B4_E3300_eps25",
        "regime": "B_high_late",
        "initialEnergy": 3300, "epsilonStart": 0.25,
        "transmitEnergy": 4.0, "receiveEnergy": 1.5,
        "target_first_death_h": "~9.5",
        "rationale": "Energy rendah (banyak mati) + EF moderat, kombinasi survival besar + delivery",
    },
    {
        "name": "B5_E3600_eps22",
        "regime": "B_high_late",
        "initialEnergy": 3600, "epsilonStart": 0.22,
        "transmitEnergy": 4.0, "receiveEnergy": 1.5,
        "target_first_death_h": "~10.1",
        "rationale": "Titik tengah halus dekat iter10 winner -> kandidat paling stabil 3-key",
    },
]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--skip-compile", action="store_true")
    p.add_argument("--resume", action="store_true")
    args = p.parse_args()
    lib.run_sweep(CONFIGS_B, suffix="B",
                  skip_compile=args.skip_compile, resume=args.resume)


if __name__ == "__main__":
    main()
