#!/usr/bin/env python3
"""Offline tests for the Crew Debug Lab ZIP analyzer."""
import importlib.util
import json
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location(
    "crew_debug_analyzer", ROOT / "scripts" / "diagnose_debug_bundle.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def make_bundle(path, events):
    session = {
        "schema": 1, "sessionId": "test", "appVersion": "1.8.115",
        "latestGeneration": 4, "latestGoal": "APP:OPEN",
        "droppedOldestEvents": 0,
    }
    with zipfile.ZipFile(path, "w") as bundle:
        bundle.writestr("session.json", json.dumps(session))
        bundle.writestr("runtime.json", json.dumps({"toolCalls": 0}))
        bundle.writestr("trace.jsonl", "".join(
            json.dumps(x) + "\n" for x in events))


def event(name, code="NONE", generation=4, action="NONE", goal=None):
    item = {
        "seq": 1, "generation": generation, "event": name,
        "call": "", "tool": "phone_action", "action": action, "code": code,
    }
    if goal is not None:
        item["goal"] = goal
    return item


def run():
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / "debug.zip"
        start = event("INTENT_FINALIZED", goal="APP:OPEN")
        make_bundle(path, [start, event("LIVE_FRAME", code="IDLE")])
        s, r, events = module.load(path)
        report = module.analyze(s, r, events)
        assert "MODEL_NO_TOOL_CALL" in report, report

        make_bundle(path, [start, event("LIVE_TOOL_CALL", action="TAP")])
        s, r, events = module.load(path)
        assert "WRONG_TOOL_ACTION" in module.analyze(s, r, events)

        make_bundle(path, [
            start, event("LIVE_TOOL_CALL", action="OPEN_APP"),
            event("RUNTIME_GATE", code="APP_OPEN_ACTION_REQUIRED")])
        s, r, events = module.load(path)
        assert "RUNTIME_GATE" in module.analyze(s, r, events)

        make_bundle(path, [
            start, event("LIVE_TOOL_CALL", action="OPEN_APP"),
            event("TOOL_RESPONSE_FAILED", code="SOCKET_CLOSED")])
        s, r, events = module.load(path)
        assert "TOOL_RESPONSE_TRANSPORT_FAILED" in module.analyze(s, r, events)

        make_bundle(path, [event("LIVE_FRAME", code="IDLE")])
        s, r, events = module.load(path)
        assert "PARTIAL_TRACE" in module.analyze(s, r, events)

        make_bundle(path, [start, event("LIVE_TOOL_CALL", action="OPEN_APP"),
                           event("TOOL_RESPONSE", code="SENT")])
        s, r, events = module.load(path)
        assert "INSUFFICIENT_EVIDENCE" in module.analyze(s, r, events)

        with zipfile.ZipFile(path, "w") as bundle:
            bundle.writestr("session.json", "{}")
        try:
            module.load(path)
            raise AssertionError("missing required bundle entries accepted")
        except ValueError:
            pass
    print("PASS debug bundle analyzer tests: 7 cases")


if __name__ == "__main__":
    run()
