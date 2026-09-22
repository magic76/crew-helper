#!/usr/bin/env python3
import math
import re
import statistics
import sys
from collections import defaultdict

PREFIX = "CREW_CONTEXT_AUDIT"

def parse_line(line):
    if PREFIX not in line:
        return None
    tail = line.split(PREFIX, 1)[1].strip()
    fields = {}
    for token in tail.split():
        if "=" not in token:
            continue
        key, value = token.split("=", 1)
        fields[key] = value
    return fields

def as_int(fields, key, default=0):
    try:
        return int(fields.get(key, default))
    except Exception:
        return default

def percentile(values, q):
    if not values:
        return 0
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    rank = (len(ordered) - 1) * q
    lo = int(math.floor(rank))
    hi = int(math.ceil(rank))
    if lo == hi:
        return ordered[lo]
    frac = rank - lo
    return int(round(ordered[lo] * (1.0 - frac) + ordered[hi] * frac))

def main():
    if len(sys.argv) != 2:
        print("usage: scripts/analyze_context_audit.py <context.log>", file=sys.stderr)
        return 2

    source_bytes = defaultdict(int)
    source_events = defaultdict(int)
    turn_totals = []
    tool_raw = defaultdict(int)
    tool_model = defaultdict(int)
    vision_raw = defaultdict(int)
    setup = {}
    budget_over = defaultdict(int)
    lines = 0

    with open(sys.argv[1], "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            fields = parse_line(line)
            if not fields:
                continue
            lines += 1
            kind = fields.get("kind", "")
            source = fields.get("source", "unknown")
            b = as_int(fields, "bytes")

            if kind == "payload":
                source_bytes[source] += b
                source_events[source] += 1
                if source.startswith("tool:"):
                    tool_raw[source] += as_int(fields, "raw")
                    tool_model[source] += as_int(fields, "model")
            elif kind == "turn_total":
                turn_totals.append(b)
            elif kind == "vision":
                vision_raw[source] += as_int(fields, "raw")
            elif kind == "setup_component":
                setup[source] = b
            elif kind == "setup_total":
                setup["setup:system_total"] = as_int(fields, "system")
                setup["setup:tools_total"] = as_int(fields, "tools")
                setup["setup:payload_total"] = b
            elif kind == "budget" and as_int(fields, "over") == 1:
                budget_over[source] += 1

    print("Context audit")
    print("=============")
    print(f"events: {lines}")

    if source_bytes:
        print("\nTop 3 injected-context sources")
        top = sorted(source_bytes.items(), key=lambda x: (-x[1], x[0]))[:3]
        for source, total in top:
            n = max(1, source_events[source])
            print(f"- {source}: total={total} B, events={n}, avg={total // n} B")
    else:
        print("\nTop 3 injected-context sources: no payload events")

    if turn_totals:
        print("\nInjected bytes per turn")
        print(f"- turns: {len(turn_totals)}")
        print(f"- avg: {int(round(statistics.mean(turn_totals)))} B")
        print(f"- p95: {percentile(turn_totals, 0.95)} B")
        print(f"- max: {max(turn_totals)} B")
    else:
        print("\nInjected bytes per turn: no completed turns")

    if setup:
        print("\nLatest setup breakdown")
        for source, value in sorted(setup.items(), key=lambda x: (-x[1], x[0])):
            print(f"- {source}: {value} B")

    if tool_raw:
        print("\nTool compaction")
        for source in sorted(tool_raw.keys()):
            raw = tool_raw[source]
            model = tool_model[source]
            ratio = 0 if raw <= 0 else int(round(model * 100.0 / raw))
            print(f"- {source}: raw={raw} B -> model={model} B ({ratio}%)")

    if vision_raw:
        print("\nVision bytes (reported separately; not mixed into text budget)")
        for source, raw in sorted(vision_raw.items(), key=lambda x: (-x[1], x[0])):
            print(f"- {source}: raw={raw} B")

    if budget_over:
        print("\nBudget overruns")
        for source, count in sorted(budget_over.items(), key=lambda x: (-x[1], x[0])):
            print(f"- {source}: {count}")
    else:
        print("\nBudget overruns: none")

    return 0

if __name__ == "__main__":
    raise SystemExit(main())
