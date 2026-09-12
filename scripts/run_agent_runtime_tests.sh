#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/app/src/main/java/com/crewpocket/helper"
OUT="$ROOT/.agent-runtime-test-classes"
mkdir -p "$OUT"
trap 'rm -rf "$OUT"' EXIT

javac -encoding UTF-8 -d "$OUT" \
  "$SRC/ActionTransaction.java" \
  "$SRC/ActionObservation.java" \
  "$SRC/ExecutionEvidence.java" \
  "$SRC/ActionVerificationResult.java" \
  "$SRC/ActionExpectation.java" \
  "$SRC/ActionVerifierV2.java" \
  "$SRC/AgentRuntimeRollout.java" \
  "$SRC/AgentEvent.java" \
  "$SRC/AgentState.java" \
  "$SRC/AgentReducer.java" \
  "$SRC/AgentLedger.java" \
  "$SRC/AgentRuntimeV2.java" \
  "$SRC/TextMatch.java" \
  "$SRC/UserActionScope.java" \
  "$SRC/SearchTransactionPolicy.java" \
  "$ROOT/tests/SendOnlyPolicyTest.java" \
  "$ROOT/tests/SearchTransactionPolicyTest.java" \
  "$ROOT/tests/ActionVerifierV2Test.java" \
  "$ROOT/tests/AgentRuntimeV2Test.java" \
  "$ROOT/tests/AgentRuntimeRolloutTest.java" \
  "$ROOT/tests/AgentReplayRunner.java"

java -cp "$OUT" com.crewpocket.helper.SendOnlyPolicyTest
java -cp "$OUT" com.crewpocket.helper.SearchTransactionPolicyTest
java -cp "$OUT" com.crewpocket.helper.ActionVerifierV2Test
java -cp "$OUT" com.crewpocket.helper.AgentRuntimeV2Test
java -cp "$OUT" com.crewpocket.helper.AgentRuntimeRolloutTest
java -cp "$OUT" com.crewpocket.helper.AgentReplayRunner "$ROOT/tests/replay"
