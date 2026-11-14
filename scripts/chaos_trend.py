#!/usr/bin/env python3
"""Appends tonight's chaos results to the running history and writes TREND.md.

    python3 scripts/chaos_trend.py <history.csv> <TREND.md> <new.csv> [<new.csv> ...]

History rows: date,scenario,expected_action,detect_s,outage_s,healed_s,result. Standard library only.
"""
import csv
import statistics
import sys
from collections import defaultdict
from pathlib import Path

FIELDS = ["date", "scenario", "expected_action", "detect_s", "outage_s", "healed_s", "result"]
RECENT_RUNS = 30


def number(value):
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def median(values):
    measured = [v for v in values if v is not None]
    return f"{statistics.median(measured):.1f} s" if measured else "not measured"


def main(argv):
    if len(argv) < 4:
        print(__doc__)
        return 2
    history_path, trend_path, new_files = Path(argv[1]), Path(argv[2]), [Path(p) for p in argv[3:]]
    rows = []
    if history_path.exists():
        with history_path.open(newline="", encoding="utf-8") as f:
            rows = [r for r in csv.DictReader(f)]
    seen = {(r["date"], r["scenario"]) for r in rows}
    for path in new_files:
        if not path.exists():
            continue
        with path.open(newline="", encoding="utf-8") as f:
            for r in csv.DictReader(f):
                key = (r.get("date"), r.get("scenario"))
                if key not in seen and all(k in r for k in FIELDS):
                    rows.append({k: r[k] for k in FIELDS})
                    seen.add(key)
    rows.sort(key=lambda r: (r["date"], r["scenario"]))
    history_path.parent.mkdir(parents=True, exist_ok=True)
    with history_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows)

    runs = sorted({r["date"] for r in rows})
    recent = set(runs[-RECENT_RUNS:])
    by_scenario = defaultdict(list)
    for r in rows:
        if r["date"] in recent:
            by_scenario[r["scenario"]].append(r)

    lines = [
        "# Nightly chaos trend",
        "",
        f"Every night CI breaks a real Kubernetes workload on kind in {len(by_scenario)} ways and measures, from outside RELAY,",
        "how long detection and healing took. Last "
        f"{len(recent)} run(s) of {len(runs)} recorded; latest {runs[-1] if runs else 'none'}.",
        "",
        "| Failure | Expected action | Runs | Healed | Median detect | Median outage | Median healed after |",
        "|---|---|---|---|---|---|---|",
    ]
    for scenario, items in sorted(by_scenario.items()):
        healed = sum(1 for r in items if r["result"] == "healed")
        lines.append(
            f"| {scenario} | {items[-1]['expected_action']} | {len(items)} | {healed} of {len(items)} | "
            f"{median(number(r['detect_s']) for r in items)} | {median(number(r['outage_s']) for r in items)} | "
            f"{median(number(r['healed_s']) for r in items)} |"
        )
    failures = [r for r in rows if r["date"] in recent and r["result"] != "healed"]
    lines += ["", "## Runs that did not heal", ""]
    lines += [f"- {r['date']}: {r['scenario']}" for r in failures] or ["None in this window."]
    lines += ["", "## Latest run", "", "| Failure | Detect | Outage | Healed after | Result |", "|---|---|---|---|---|"]
    for r in rows:
        if runs and r["date"] == runs[-1]:
            lines.append(f"| {r['scenario']} | {r['detect_s'] or '?'} s | {(r['outage_s'] + ' s') if r['outage_s'] else 'none'} | {r['healed_s']} s | {r['result']} |")
    trend_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{len(rows)} rows in history, {len(runs)} runs; wrote {trend_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
