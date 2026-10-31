#!/usr/bin/env python3
"""Offline Crew Debug Lab bundle analyzer. No network or third-party packages."""
import argparse
import json
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

REQUIRED = {"session.json", "trace.jsonl", "runtime.json"}
MAX_ENTRY = 2_000_000


def load(path):
    with zipfile.ZipFile(path) as bundle:
        missing = REQUIRED.difference(bundle.namelist())
        if missing:
            raise ValueError("Missing bundle files: " + ", ".join(sorted(missing)))
        for name in REQUIRED:
            if bundle.getinfo(name).file_size > MAX_ENTRY:
                raise ValueError("Oversized bundle entry: " + name)
        session = json.loads(bundle.read("session.json"))
        runtime = json.loads(bundle.read("runtime.json"))
        events = []
        for line in bundle.read("trace.jsonl").decode("utf-8").splitlines():
            if line.strip():
                events.append(json.loads(line))
    if session.get("schema") != 1:
        raise ValueError("Unsupported schema")
    if not isinstance(events, list) or len(events) > 512:
        raise ValueError("Invalid event count")
    return session, runtime, events


def analyze(session, runtime, events):
    by_gen = defaultdict(list)
    for event in events:
        by_gen[event.get("generation")].append(event)
    out = [
        "# Crew Debug Lab — local analysis",
        "",
        "Session: `" + str(session["sessionId"]) + "`",
        "Build: `" + str(session.get("appVersion", "?")) + "`",
        "Captured events: " + str(len(events)),
        "Dropped oldest events: " + str(session.get("droppedOldestEvents", 0)),
        "",
    ]
    for gen in sorted(k for k in by_gen if isinstance(k, int) and k >= 0):
        history = by_gen[gen]
        events_by_name = defaultdict(list)
        for e in history:
            events_by_name[e.get("event")].append(e)
        calls = events_by_name["LIVE_TOOL_CALL"]
        replies = events_by_name["TOOL_RESPONSE"]
        canceled = events_by_name["TOOL_CANCELLED"]
        gates = events_by_name["RUNTIME_GATE"]
        frames = events_by_name["LIVE_FRAME"]
        actions = [e.get("action") for e in calls]
        idle = any(e.get("code") == "IDLE" for e in frames)
        started = [e for e in history if e.get("event") == "INTENT_FINALIZED"]
        goal = started[-1].get("goal", "UNKNOWN") if started else "UNKNOWN"
        if not started:
            classification = "PARTIAL_TRACE"
            rationale = "The intent start is missing from the captured window."
        elif "TAP" in actions and goal == "APP:OPEN":
            classification = "WRONG_TOOL_ACTION"
            rationale = "The explicit app-open intent received a TAP instead of OPEN_APP."
        elif gates:
            classification = "RUNTIME_GATE"
            rationale = "A Runtime preflight or generation gate blocked a tool."
        elif not calls and idle:
            classification = "MODEL_NO_TOOL_CALL"
            rationale = "The interaction became IDLE without an observed tool call."
        elif events_by_name["TOOL_RESPONSE_FAILED"]:
            classification = "TOOL_RESPONSE_TRANSPORT_FAILED"
            rationale = "A tool result could not be delivered to the Live websocket."
        elif events_by_name["TASK_CANCELLED"]:
            classification = "TASK_CANCELLED"
            rationale = ("The task was cancelled after this intent. "
                         "Review the task cancellation code and compare it to "
                         "tool completion; a SENT response alone does not prove "
                         "the whole user goal was achieved.")
        elif events_by_name["TASK_FINISHED"]:
            classification = "TASK_FINISHED"
            rationale = ("Runtime recorded a terminal lifecycle result. "
                         "Verify its completion evidence independently.")
        elif calls and not replies and not canceled:
            classification = "TOOL_RESPONSE_MISSING"
            rationale = "Tool calls were received but no response appears in this capture."
        elif len(replies) > 0 and any(e.get("code") not in ("SENT", "NONE") for e in replies):
            classification = "TOOL_FAILED"
            rationale = "At least one tool response reported failure."
        else:
            classification = "INSUFFICIENT_EVIDENCE"
            rationale = "This timeline alone does not establish a terminal failure."
        out.extend([
            "## Generation " + str(gen),
            "",
            "Classification: **" + classification + "**",
            "",
            rationale,
            "",
            "Tool calls: " + str(len(calls)) + " · replies: " + str(len(replies))
            + " · cancellations: " + str(len(canceled)),
            "",
        ])
        if events_by_name["TOOL_RUNTIME_DONE"]:
            runtimes = events_by_name["TOOL_RUNTIME_DONE"]
            out.append("Observed tool runtime: " + ", ".join(
                str(x.get("count", 0)) + " ms"
                for x in runtimes[:10]))
            out.append("")
        if events_by_name["TASK_CANCELLED"] or events_by_name["TASK_FINISHED"]:
            terminals = events_by_name["TASK_CANCELLED"] + events_by_name["TASK_FINISHED"]
            out.append("Task end: " + ", ".join(
                str(x.get("event")) + " / " + str(x.get("code"))
                for x in terminals))
            out.append("")
        if gates:
            out.append("Gate codes: " + ", ".join(sorted(set(
                e.get("code", "UNKNOWN") for e in gates))))
            out.append("")
        if calls:
            out.append("Requested tool/action: " + ", ".join(
                str(e.get("tool")) + "/" + str(e.get("action")) for e in calls[:12]))
            out.append("")
    if not by_gen or all(k < 0 for k in by_gen):
        out.append("Insufficient evidence: no finalized intent recorded.")
    out.extend([
        "## Next verification",
        "",
        "Inspect the first unexpected event; correlate with source code and"
        " add a failing regression test before applying a fix.",
        "Do not assume a successful tool response proves a verified Android side effect.",
        "Classification is a hypothesis from bounded evidence, not confirmed root cause.",
    ])
    return "\n".join(out) + "\n"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("bundle", type=Path, help="ZIP shared from Crew Helper")
    parser.add_argument("--out", type=Path, help="optional Markdown output path")
    args = parser.parse_args()
    try:
        session, runtime, events = load(args.bundle)
        report = analyze(session, runtime, events)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        print("Bundle invalid: " + str(error), file=sys.stderr)
        return 2
    if args.out:
        args.out.write_text(report, encoding="utf-8")
    print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
