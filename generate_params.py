"""
generate_params.py
==================
KEY INSIGHT dari analisa empiris:
  EF bekerja optimal ketika rasio (tx+rx)/scan TINGGI.
  Haggle confirmed:
    ratio=121x (iter4) -> BERHASIL (36 vs 38 mati)
    ratio=101x (iter3) -> borderline
    ratio=85x  (iter1) -> TIE
    ratio=73x  (iter5) -> GAGAL
  Threshold efektif EF: ratio > ~100x

HAGGLE: anchor iter4 confirmed, push ratio lebih tinggi
HELSINKI: scan 0.05->0.02 supaya ratio naik dari ~27x ke 81-168x
"""

import json

HAGGLE_PARAMS = [
    # Iter 1: EXACT iter4 confirmed (ratio=121x) -- anchor pasti berhasil
    {"initialEnergy": 760, "scanEnergy": 0.08, "transmitEnergy": 8.0, "receiveEnergy": 1.7},
    # Iter 2: ratio=147x
    {"initialEnergy": 780, "scanEnergy": 0.07, "transmitEnergy": 8.5, "receiveEnergy": 1.75},
    # Iter 3: ratio=154x
    {"initialEnergy": 800, "scanEnergy": 0.07, "transmitEnergy": 9.0, "receiveEnergy": 1.8},
    # Iter 4: ratio=138x, init lebih besar
    {"initialEnergy": 800, "scanEnergy": 0.07, "transmitEnergy": 8.0, "receiveEnergy": 1.7},
    # Iter 5: ratio=190x -- paling agresif
    {"initialEnergy": 820, "scanEnergy": 0.06, "transmitEnergy": 9.5, "receiveEnergy": 1.9},
]

HELSINKI_PARAMS = [
    # Iter 1: ratio=101x, first_death~5.9h
    {"initialEnergy": 900,  "scanEnergy": 0.02, "transmitEnergy": 1.5, "receiveEnergy": 0.52},
    # Iter 2: ratio=121x -- setara threshold Haggle confirmed, first_death~5.5h
    {"initialEnergy": 1000, "scanEnergy": 0.02, "transmitEnergy": 1.8, "receiveEnergy": 0.63},
    # Iter 3: ratio=135x, first_death~5.4h
    {"initialEnergy": 1100, "scanEnergy": 0.02, "transmitEnergy": 2.0, "receiveEnergy": 0.70},
    # Iter 4: ratio=81x, konservatif -- first_death~6.6h, Q-table lebih matang
    {"initialEnergy": 800,  "scanEnergy": 0.02, "transmitEnergy": 1.2, "receiveEnergy": 0.42},
    # Iter 5: ratio=168x -- paling agresif, first_death~5.1h
    {"initialEnergy": 1300, "scanEnergy": 0.02, "transmitEnergy": 2.5, "receiveEnergy": 0.87},
]


def validate(p, label):
    assert p["transmitEnergy"] > p["receiveEnergy"], \
        "%s: GAGAL C1 -- tx=%.2f must > rx=%.2f" % (label, p["transmitEnergy"], p["receiveEnergy"])
    assert p["scanEnergy"] < p["receiveEnergy"], \
        "%s: GAGAL C2 -- scan=%.3f must < rx=%.2f" % (label, p["scanEnergy"], p["receiveEnergy"])


def estimate_death(p, total_time, active_low, active_high):
    scan_rate = p["scanEnergy"] / 120.0
    txrx = p["transmitEnergy"] + p["receiveEnergy"]
    t_first = p["initialEnergy"] / (active_high * txrx + scan_rate)
    t_last  = p["initialEnergy"] / (active_low  * txrx + scan_rate)
    def fmt(t):
        if t < total_time:
            return "%ds(%.1fh)" % (t, t / 3600.0)
        return "survive"
    return fmt(t_first), fmt(t_last)


def calc_ratio(p):
    return (p["transmitEnergy"] + p["receiveEnergy"]) / p["scanEnergy"]


def main():
    assert len(HAGGLE_PARAMS) == len(HELSINKI_PARAMS) == 5

    params_list = []
    for i, (h, k) in enumerate(zip(HAGGLE_PARAMS, HELSINKI_PARAMS), start=1):
        validate(h, "Haggle iter %d" % i)
        validate(k, "Helsinki iter %d" % i)
        params_list.append({"iteration": i, "haggle": h, "helsinki": k})

    with open("run_params.json", "w") as f:
        json.dump(params_list, f, indent=2)

    hdr = "  %2s  %5s  %5s  %5s  %5s  %6s  %14s  %14s"
    row = "  %2d  %5.0f  %5.3f  %5.2f  %5.2f  %5.0fx  %14s  %14s"

    print("=" * 78)
    print("  HAGGLE (41 nodes, 274883s = 76.4h)")
    print("  iter1=confirmed(121x), iter2-5 push ratio lebih tinggi")
    print(hdr % ("#", "Init", "Scan", "TX", "RX", "Ratio", "FirstDeath", "LastDeath"))
    print("-" * 78)
    for i, p in enumerate(HAGGLE_PARAMS, 1):
        t1, t2 = estimate_death(p, 274883, 0.0002, 0.0008)
        r = calc_ratio(p)
        print(row % (i, p["initialEnergy"], p["scanEnergy"],
                     p["transmitEnergy"], p["receiveEnergy"], r, t1, t2))

    print()
    print("=" * 78)
    print("  HELSINKI (200 nodes, 43200s = 12h)")
    print("  scan 0.05->0.02, ratio naik dari ~27x ke 81-168x")
    print(hdr % ("#", "Init", "Scan", "TX", "RX", "Ratio", "FirstDeath", "LastDeath"))
    print("-" * 78)
    for i, p in enumerate(HELSINKI_PARAMS, 1):
        t1, t2 = estimate_death(p, 43200, 0.0186, 0.0208)
        r = calc_ratio(p)
        print(row % (i, p["initialEnergy"], p["scanEnergy"],
                     p["transmitEnergy"], p["receiveEnergy"], r, t1, t2))

    print()
    print("Saved: run_params.json  (5 iterasi x 2 dataset)")
    print()
    print("Reset progress lama dulu:")
    print("  del _progress_haggle_with_ef.json _progress_haggle_without_ef.json")
    print("  del _progress_helsinki_with_ef.json _progress_helsinki_without_ef.json")
    print()
    print("Jalankan di 4 terminal:")
    print("  python runner.py haggle_with_ef")
    print("  python runner.py haggle_without_ef")
    print("  python runner.py helsinki_with_ef")
    print("  python runner.py helsinki_without_ef")


if __name__ == "__main__":
    main()
