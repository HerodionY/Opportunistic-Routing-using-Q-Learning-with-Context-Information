"""
REGIME A — GENTLE / MID-CASCADE (taruhan margin BESAR).

Energy moderate (2200-2500) + drain SANGAT rendah (TX 2.0-2.5, RX 0.6-0.8).
Hipotesis: cascade jatuh di tengah simulasi (5-8h) -> WithoutEF kehilangan relay
penting saat banyak pesan masih beredar -> delivery NoEF jeblok -> EF menang
survival BESAR sekaligus delivery.

RISIKO (jujur): satu-satunya data gentle yang ada (iter5, TX3.0) malah bikin EF
mati DULUAN dan KALAH delivery. Regime A ini lebih gentle lagi (TX2.0-2.5) untuk
menggeser cascade ke tengah — tapi belum terbukti. Ini half yang berisiko.

Jalankan BARENGAN dengan run_5_combined_B.py di terminal terpisah (output 1 folder
Z_combined/, CSV terpisah summary_A.csv / summary_B.csv -> tidak bentrok).

Usage:
    python run_5_combined_A.py
    python run_5_combined_A.py --skip-compile
    python run_5_combined_A.py --resume
"""

import argparse
import _sweep_lib as lib

CONFIGS_A = [
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
        "initialEnergy": 2400, "epsilonStart": 0.30,
        "transmitEnergy": 2.5, "receiveEnergy": 0.8,
        "target_first_death_h": "6-7",
        "rationale": "Cascade mid-sim + EF agak dominan (eps0.30), balance survival vs delivery",
    },
    {
        "name": "A3_gentle_strongEF",
        "regime": "A_gentle_mid",
        "initialEnergy": 2400, "epsilonStart": 0.25,
        "transmitEnergy": 2.5, "receiveEnergy": 0.8,
        "target_first_death_h": "6-7",
        "rationale": "Sama A2 tapi EF lebih dominan (eps0.25 -> 75% exploit Q-table)",
    },
    {
        "name": "A4_very_gentle",
        "regime": "A_gentle_mid",
        "initialEnergy": 2500, "epsilonStart": 0.40,
        "transmitEnergy": 2.0, "receiveEnergy": 0.6,
        "target_first_death_h": "7-8",
        "rationale": "Drain paling rendah -> cascade paling bertahap, EF maksimal demonstrasi",
    },
    {
        "name": "A5_balanced_mid",
        "regime": "A_gentle_mid",
        "initialEnergy": 2300, "epsilonStart": 0.30,
        "transmitEnergy": 2.5, "receiveEnergy": 0.8,
        "target_first_death_h": "5-6",
        "rationale": "Gentle drain + strong EF + early-mid cascade, kandidat sweet spot regime A",
    },
]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--skip-compile", action="store_true")
    p.add_argument("--resume", action="store_true")
    args = p.parse_args()
    lib.run_sweep(CONFIGS_A, suffix="A",
                  skip_compile=args.skip_compile, resume=args.resume)


if __name__ == "__main__":
    main()
