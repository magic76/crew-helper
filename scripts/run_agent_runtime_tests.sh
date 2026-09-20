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
  "$SRC/ActionRecoveryPolicy.java" \
  "$SRC/AgentTaskLifecyclePolicy.java" \
  "$SRC/AgentRuntimeRollout.java" \
  "$SRC/AgentEvent.java" \
  "$SRC/AgentState.java" \
  "$SRC/AgentReducer.java" \
  "$SRC/AgentLedger.java" \
  "$SRC/AgentRuntimeV2.java" \
  "$SRC/TextMatch.java" \
  "$SRC/TextEntryGoalGuard.java" \
  "$SRC/SendAuthorization.java" \
  "$SRC/LiveTurnCoordinator.java" \
  "$SRC/ToolCallDispatchLedger.java" \
  "$SRC/RuntimeToolRouting.java" \
  "$SRC/DeckTurnAdvancePolicy.java" \
  "$SRC/UiChangeSignal.java" \
  "$SRC/RuntimeUiState.java" \
  "$SRC/LearnedUiScopePolicy.java" \
  "$SRC/UserActionScope.java" \
  "$SRC/PendingActionPolicy.java" \
  "$SRC/PendingWaitEventPolicy.java" \
  "$SRC/SessionContextPrompt.java" \
  "$SRC/SearchTransactionPolicy.java" \
  "$SRC/ScrollDirectionPolicy.java" \
  "$SRC/VisionCoordinateMapper.java" \
  "$SRC/ElementReferenceCommand.java" \
  "$SRC/ElementReferenceChoice.java" \
  "$SRC/ElementReferenceLayout.java" \
  "$SRC/GoalTaskContinuityPolicy.java" \
  "$SRC/InspectorTaskState.java" \
  "$SRC/InspectorTraceScope.java" \
  "$SRC/ReflectionLearningPolicy.java" \
  "$SRC/ReflectionRuleEvidence.java" \
  "$SRC/ExperienceTriggerPolicy.java" \
  "$SRC/ActiveNoiseAdmissionGate.java" \
  "$SRC/AppSemanticConcept.java" \
  "$SRC/GoogleMapsSemanticContract.java" \
  "$SRC/AgentTapDiagnostic.java" \
  "$SRC/ModelStepGuidance.java" \
  "$SRC/AppPlaybookRelevance.java" \
  "$SRC/TaskRecipePolicy.java" \
  "$ROOT/tests/SendAuthorizationTest.java" \
  "$ROOT/tests/LiveTurnCoordinatorTest.java" \
  "$ROOT/tests/ToolCallDispatchLedgerTest.java" \
  "$ROOT/tests/RuntimeToolRoutingTest.java" \
  "$ROOT/tests/DeckTurnAdvancePolicyTest.java" \
  "$ROOT/tests/UiChangeSignalTest.java" \
  "$ROOT/tests/RuntimeUiStateTest.java" \
  "$ROOT/tests/LearnedUiScopePolicyTest.java" \
  "$ROOT/tests/SendOnlyPolicyTest.java" \
  "$ROOT/tests/PendingActionPolicyTest.java" \
  "$ROOT/tests/PendingWaitEventPolicyTest.java" \
  "$ROOT/tests/SessionContextPromptTest.java" \
  "$ROOT/tests/SearchTransactionPolicyTest.java" \
  "$ROOT/tests/ScrollDirectionPolicyTest.java" \
  "$ROOT/tests/ActionVerifierV2Test.java" \
  "$ROOT/tests/ActionRecoveryPolicyTest.java" \
  "$ROOT/tests/AgentTaskLifecyclePolicyTest.java" \
  "$ROOT/tests/AgentRuntimeV2Test.java" \
  "$ROOT/tests/AgentRuntimeRolloutTest.java" \
  "$ROOT/tests/VisionCoordinateMapperTest.java" \
  "$ROOT/tests/ElementReferenceCommandTest.java" \
  "$ROOT/tests/ElementReferenceChoiceTest.java" \
  "$ROOT/tests/ElementReferenceLayoutTest.java" \
  "$ROOT/tests/GoalTaskContinuityPolicyTest.java" \
  "$ROOT/tests/InspectorTaskStateTest.java" \
  "$ROOT/tests/InspectorTraceScopeTest.java" \
  "$ROOT/tests/ReflectionLearningPolicyTest.java" \
  "$ROOT/tests/ReflectionRuleEvidenceTest.java" \
  "$ROOT/tests/ExperienceTriggerPolicyTest.java" \
  "$ROOT/tests/ActiveNoiseAdmissionGateTest.java" \
  "$ROOT/tests/TextEntryGoalGuardTest.java" \
  "$ROOT/tests/GoogleMapsSemanticContractTest.java" \
  "$ROOT/tests/AgentTapDiagnosticTest.java" \
  "$ROOT/tests/ModelStepGuidanceTest.java" \
  "$ROOT/tests/AppPlaybookRelevanceTest.java" \
  "$ROOT/tests/TaskRecipePolicyTest.java" \
  "$ROOT/tests/AgentReplayRunner.java"

java -cp "$OUT" com.crewpocket.helper.SendAuthorizationTest
java -cp "$OUT" com.crewpocket.helper.LiveTurnCoordinatorTest
java -cp "$OUT" com.crewpocket.helper.ToolCallDispatchLedgerTest
java -cp "$OUT" com.crewpocket.helper.RuntimeToolRoutingTest
java -cp "$OUT" com.crewpocket.helper.DeckTurnAdvancePolicyTest
java -cp "$OUT" com.crewpocket.helper.UiChangeSignalTest
java -cp "$OUT" com.crewpocket.helper.RuntimeUiStateTest
java -cp "$OUT" com.crewpocket.helper.LearnedUiScopePolicyTest
java -cp "$OUT" com.crewpocket.helper.SendOnlyPolicyTest
java -cp "$OUT" com.crewpocket.helper.PendingActionPolicyTest
java -cp "$OUT" com.crewpocket.helper.PendingWaitEventPolicyTest
java -cp "$OUT" com.crewpocket.helper.SessionContextPromptTest
java -cp "$OUT" com.crewpocket.helper.SearchTransactionPolicyTest
java -cp "$OUT" com.crewpocket.helper.ScrollDirectionPolicyTest
java -cp "$OUT" com.crewpocket.helper.ActionVerifierV2Test
java -cp "$OUT" com.crewpocket.helper.ActionRecoveryPolicyTest
java -cp "$OUT" com.crewpocket.helper.AgentTaskLifecyclePolicyTest
java -cp "$OUT" com.crewpocket.helper.AgentRuntimeV2Test
java -cp "$OUT" com.crewpocket.helper.AgentRuntimeRolloutTest
java -cp "$OUT" com.crewpocket.helper.VisionCoordinateMapperTest
java -cp "$OUT" com.crewpocket.helper.ElementReferenceCommandTest
java -cp "$OUT" com.crewpocket.helper.ElementReferenceChoiceTest
java -cp "$OUT" com.crewpocket.helper.ElementReferenceLayoutTest
java -cp "$OUT" com.crewpocket.helper.GoalTaskContinuityPolicyTest
java -cp "$OUT" com.crewpocket.helper.InspectorTaskStateTest
java -cp "$OUT" com.crewpocket.helper.InspectorTraceScopeTest
java -cp "$OUT" com.crewpocket.helper.ReflectionLearningPolicyTest
java -cp "$OUT" com.crewpocket.helper.ReflectionRuleEvidenceTest
java -cp "$OUT" com.crewpocket.helper.ExperienceTriggerPolicyTest
java -cp "$OUT" com.crewpocket.helper.ActiveNoiseAdmissionGateTest
java -cp "$OUT" com.crewpocket.helper.TextEntryGoalGuardTest
java -cp "$OUT" com.crewpocket.helper.GoogleMapsSemanticContractTest
java -cp "$OUT" com.crewpocket.helper.AgentTapDiagnosticTest
java -cp "$OUT" com.crewpocket.helper.ModelStepGuidanceTest
java -cp "$OUT" com.crewpocket.helper.AppPlaybookRelevanceTest
java -cp "$OUT" com.crewpocket.helper.TaskRecipePolicyTest
java -cp "$OUT" com.crewpocket.helper.AgentReplayRunner "$ROOT/tests/replay"
