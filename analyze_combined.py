"""
Gabungkan hasil summary_A.csv + summary_B.csv dari Z_combined/ dan tampilkan
ranking 3-METRIK KUNCI + perbandingan regime A vs B.

3 metrik kunci:
    1. survival : survival_diff > 0
    2. longevity: cascade_gap_h > 0 (EF capai 50 kematian lebih lambat)
    3. delivery : delivery_diff_pct > 0

Usage:
    python analyze_combined.py
"""

import csv
import sys
from pathlib import Path

PROJECT_DIR = Path(__file__).resolve().parent
ITER_DIR = PROJECT_DIR / "Z_combined"


def load(suffix):
    path = ITER_DIR / f"summary_{suffix}.csv"
    if not path.exists():
        return []
    rows = []
    with open(path, "r", encoding="utf-8") as f:
        for row in csv.DictReader(f):
            try:
                row["_surv"] = int(row["survival_diff"]) if row["survival_diff"] else -999
                row["_casc"] = float(row["cascade_gap_h"]) if row["cascade_gap_h"] else -999
                row["_deliv"] = float(row["delivery_diff_pct"]) if row["delivery_diff_pct"] else -999
                row["_3key"] = int(row["three_key_count"]) if row["three_key_count"] else 0
                row["_deaths"] = int(row["withEF_deaths"]) if row["withEF_deaths"] else -1
                rows.append(row)
            except (ValueError, TypeError):
                continue
    return rows


def composite(r):
    # urut: jumlah 3-key, lalu survival, lalu delivery, lalu cascade gap
    return (r["_3key"], r["_surv"], r["_deliv"], r["_casc"])


def show(r):
    print(f"  {r['iter_name']:<20} [{r['regime']:<12}]  "
          f"E={r['initialEnergy']:<5} eps={r['epsilonStart']:<5} "
          f"TX={r['transmitEnergy']:<4} RX={r['receiveEnergy']}")
    print(f"      survival {r['_surv']:+d}  |  cascade-gap {r['_casc']:+.2f}h  |  "
          f"delivery {r['_deliv']:+.2f}%  |  3-key {r['_3key']}/3")
    print(f"      EF deaths={r['withEF_deaths']}, NoEF deaths={r['withoutEF_deaths']}  "
          f"|  verdict: {r['verdict']}")


def main():
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

    rows_a = load("A")
    rows_b = load("B")
    allrows = rows_a + rows_b

    if not allrows:
        print("Belum ada hasil. Jalankan run_5_combined_A.py / run_5_combined_B.py dulu.")
        return 1

    valid = [r for r in allrows if 20 <= r["_deaths"] <= 190]
    pool = valid if valid else allrows

    print("=" * 72)
    print("  HASIL GABUNGAN A + B — 3 METRIK KUNCI")
    print("  (survival, longevity=cascade speed, delivery)")
    print("=" * 72)

    three_key = [r for r in pool if r["_3key"] == 3]
    print(f"\n  Config A selesai: {len(rows_a)}/5   Config B selesai: {len(rows_b)}/5")
    print(f"  Config yang kena KETIGA metrik: {len(three_key)}")

    if three_key:
        print(f"\n  {'='*68}")
        print(f"  🏆 JUARA (3-KEY WIN), diurut terbaik:")
        print(f"  {'='*68}")
        for r in sorted(three_key, key=composite, reverse=True):
            show(r)
            print()
    else:
        print(f"\n  Tidak ada yang kena ketiga metrik. Kandidat terbaik (2/3):")
        best = max(pool, key=composite)
        show(best)

    # Perbandingan regime
    print(f"\n  {'-'*68}")
    print(f"  PERBANDINGAN REGIME (untuk skripsi: bukti trade-off fundamental?):")
    print(f"  {'-'*68}")
    for suffix, label, rows in [("A", "A gentle/mid", rows_a), ("B", "B high/late", rows_b)]:
        v = [r for r in rows if 20 <= r["_deaths"] <= 190]
        p = v if v else rows
        if not p:
            print(f"\n  Regime {label}: belum ada hasil valid")
            continue
        best = max(p, key=composite)
        n_3key = sum(1 for r in p if r["_3key"] == 3)
        print(f"\n  Regime {label}: {n_3key}/{len(rows)} kena 3-key. Terbaik:")
        show(best)

    # Tabel ringkas semua
    print(f"\n  {'-'*68}")
    print(f"  TABEL RINGKAS SEMUA CONFIG (urut composite):")
    print(f"  {'-'*68}")
    print(f"  {'config':<20} {'surv':>5} {'casc-h':>7} {'deliv%':>7} {'3key':>5}  verdict")
    for r in sorted(allrows, key=composite, reverse=True):
        print(f"  {r['iter_name']:<20} {r['_surv']:>+5d} {r['_casc']:>+7.2f} "
              f"{r['_deliv']:>+7.2f} {r['_3key']:>4}/3  {r['verdict'].split(' (')[0]}")

    print("\n" + "=" * 72)
    print("  Setelah pilih pemenang, jalankan visualize untuk chart cantik.")
    print("=" * 72)
    return 0


if __name__ == "__main__":
    sys.exit(main())
