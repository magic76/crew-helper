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
  "$SRC/LocatorConfidencePolicy.java" \
  "$SRC/LocatorFallbackPolicy.java" \
  "$SRC/AgentTaskLifecyclePolicy.java" \
  "$SRC/AgentTaskLifecycleClock.java" \
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
  "$SRC/LiveTurnOrderingPolicy.java" \
  "$SRC/LiveHumanTurnBoundary.java" \
  "$SRC/LiveModelProgressPolicy.java" \
  "$SRC/MediaPlaybackCompletionPolicy.java" \
  "$SRC/MediaGoalUiPolicy.java" \
  "$SRC/MediaTapRecoveryPolicy.java" \
  "$SRC/RefinedMemoryPolicy.java" \
  "$SRC/RefinedMemoryEvidencePolicy.java" \
  "$SRC/RefinedMemoryCorrectionPolicy.java" \
  "$SRC/RefinedMemoryDashboardPolicy.java" \
  "$SRC/RefinedMemoryUseTrace.java" \
  "$SRC/AgentTaskEndReason.java" \
  "$SRC/GoogleMapsNavigationStatePolicy.java" \
  "$SRC/TaskRecipeCompletionPolicy.java" \
  "$SRC/ToolCallDispatchLedger.java" \
  "$SRC/RuntimeToolRouting.java" \
  "$SRC/ToolIntentRoutingPolicy.java" \
  "$SRC/DeckTurnAdvancePolicy.java" \
  "$SRC/UiChangeSignal.java" \
  "$SRC/RuntimeUiState.java" \
  "$SRC/LearnedUiScopePolicy.java" \
  "$SRC/UserActionScope.java" \
  "$SRC/PendingActionPolicy.java" \
  "$SRC/PendingWaitEventPolicy.java" \
  "$SRC/SessionContextPrompt.java" \
  "$SRC/SearchTransactionPolicy.java" \
  "$SRC/SearchResultAutonomyPolicy.java" \
  "$SRC/ScrollDirectionPolicy.java" \
  "$SRC/VisionCoordinateMapper.java" \
  "$SRC/VisualTapLease.java" \
  "$SRC/ElementReferenceCommand.java" \
  "$SRC/ElementReferenceChoice.java" \
  "$SRC/ElementReferenceLayout.java" \
  "$SRC/GoalTaskContinuityPolicy.java" \
  "$SRC/GoalIntentKey.java" \
  "$SRC/UserRetryAfterUnconfirmedOutcomePolicy.java" \
  "$SRC/InspectorTaskState.java" \
  "$SRC/InspectorTraceScope.java" \
  "$SRC/ReflectionLearningPolicy.java" \
  "$SRC/ReflectionRuleEvidence.java" \
  "$SRC/ExperienceTriggerPolicy.java" \
  "$SRC/ExperiencePolicyEpoch.java" \
  "$SRC/ActiveNoiseAdmissionGate.java" \
  "$SRC/AppSemanticConcept.java" \
  "$SRC/GoogleMapsSemanticContract.java" \
  "$SRC/AgentTapDiagnostic.java" \
  "$SRC/ModelStepGuidance.java" \
  "$SRC/ModelRuntimeContract.java" \
  "$SRC/LivePrompt.java" \
  "$SRC/ModelScreenPriorityPolicy.java" \
  "$SRC/InformationAnswerFastPathPolicy.java" \
  "$SRC/AppPlaybookRelevance.java" \
  "$SRC/TaskRecipePolicy.java" \
  "$SRC/VoiceCommandQualityPolicy.java" \
  "$SRC/ActionSafetyPolicy.java" \
  "$SRC/CommitGuard.java" \
  "$SRC/AppAutonomyPolicy.java" \
  "$SRC/VoiceExecutionPolicy.java" \
  "$SRC/BubbleLogoStatePolicy.java" \
  "$SRC/ConversationLoopPolicy.java" \
  "$SRC/ConversationLoopTakeoverPolicy.java" \
  "$SRC/ConversationLoopRecipe.java" \
  "$SRC/DelegatedSendLease.java" \
  "$SRC/ConversationLoopWakePolicy.java" \
  "$SRC/ContextPayloadBudget.java" \
  "$SRC/ScreenItemPriorityPolicy.java" \
  "$ROOT/tests/SendAuthorizationTest.java" \
  "$ROOT/tests/LiveTurnCoordinatorTest.java" \
  "$ROOT/tests/LiveTurnOrderingPolicyTest.java" \
  "$ROOT/tests/LiveHumanTurnBoundaryTest.java" \
  "$ROOT/tests/LiveModelProgressPolicyTest.java" \
  "$ROOT/tests/MediaPlaybackCompletionPolicyTest.java" \
  "$ROOT/tests/MediaGoalUiPolicyTest.java" \
  "$ROOT/tests/MediaTapRecoveryPolicyTest.java" \
  "$ROOT/tests/RefinedMemoryPolicyTest.java" \
  "$ROOT/tests/RefinedMemoryEvidencePolicyTest.java" \
  "$ROOT/tests/RefinedMemoryCorrectionPolicyTest.java" \
  "$ROOT/tests/RefinedMemoryDashboardPolicyTest.java" \
  "$ROOT/tests/RefinedMemoryUseTraceTest.java" \
  "$ROOT/tests/GoogleMapsNavigationStatePolicyTest.java" \
  "$ROOT/tests/TaskRecipeCompletionPolicyTest.java" \
  "$ROOT/tests/ToolCallDispatchLedgerTest.java" \
  "$ROOT/tests/RuntimeToolRoutingTest.java" \
  "$ROOT/tests/ToolIntentRoutingPolicyTest.java" \
  "$ROOT/tests/DeckTurnAdvancePolicyTest.java" \
  "$ROOT/tests/UiChangeSignalTest.java" \
  "$ROOT/tests/RuntimeUiStateTest.java" \
  "$ROOT/tests/LearnedUiScopePolicyTest.java" \
  "$ROOT/tests/SendOnlyPolicyTest.java" \
  "$ROOT/tests/UserActionScopeTest.java" \
  "$ROOT/tests/PendingActionPolicyTest.java" \
  "$ROOT/tests/PendingWaitEventPolicyTest.java" \
  "$ROOT/tests/SessionContextPromptTest.java" \
  "$ROOT/tests/SearchTransactionPolicyTest.java" \
  "$ROOT/tests/SearchResultAutonomyPolicyTest.java" \
  "$ROOT/tests/ScrollDirectionPolicyTest.java" \
  "$ROOT/tests/ActionVerifierV2Test.java" \
  "$ROOT/tests/ActionRecoveryPolicyTest.java" \
  "$ROOT/tests/LocatorConfidencePolicyTest.java" \
  "$ROOT/tests/AgentTaskLifecyclePolicyTest.java" \
  "$ROOT/tests/AgentTaskLifecycleClockTest.java" \
  "$ROOT/tests/AgentRuntimeV2Test.java" \
  "$ROOT/tests/AgentRuntimeRolloutTest.java" \
  "$ROOT/tests/VisionCoordinateMapperTest.java" \
  "$ROOT/tests/VisualTapLeaseTest.java" \
  "$ROOT/tests/ElementReferenceCommandTest.java" \
  "$ROOT/tests/ElementReferenceChoiceTest.java" \
  "$ROOT/tests/ElementReferenceLayoutTest.java" \
  "$ROOT/tests/GoalTaskContinuityPolicyTest.java" \
  "$ROOT/tests/GoalIntentKeyTest.java" \
  "$ROOT/tests/UserRetryAfterUnconfirmedOutcomePolicyTest.java" \
  "$ROOT/tests/InspectorTaskStateTest.java" \
  "$ROOT/tests/InspectorTraceScopeTest.java" \
  "$ROOT/tests/ReflectionLearningPolicyTest.java" \
  "$ROOT/tests/ReflectionRuleEvidenceTest.java" \
  "$ROOT/tests/ExperienceTriggerPolicyTest.java" \
  "$ROOT/tests/ExperiencePolicyEpochTest.java" \
  "$ROOT/tests/ActiveNoiseAdmissionGateTest.java" \
  "$ROOT/tests/TextEntryGoalGuardTest.java" \
  "$ROOT/tests/GoogleMapsSemanticContractTest.java" \
  "$ROOT/tests/AgentTapDiagnosticTest.java" \
  "$ROOT/tests/ModelStepGuidanceTest.java" \
  "$ROOT/tests/ModelRuntimeContractTest.java" \
  "$ROOT/tests/LivePromptTest.java" \
  "$ROOT/tests/ModelScreenPriorityPolicyTest.java" \
  "$ROOT/tests/InformationAnswerFastPathPolicyTest.java" \
  "$ROOT/tests/AppPlaybookRelevanceTest.java" \
  "$ROOT/tests/TaskRecipePolicyTest.java" \
  "$ROOT/tests/VoiceCommandQualityPolicyTest.java" \
  "$ROOT/tests/VoiceExecutionPolicyTest.java" \
  "$ROOT/tests/BubbleLogoStatePolicyTest.java" \
  "$ROOT/tests/CommitGuardTest.java" \
  "$ROOT/tests/AppAutonomyPolicyTest.java" \
  "$ROOT/tests/ConversationLoopRecipeTest.java" \
  "$ROOT/tests/ConversationLoopTakeoverPolicyTest.java" \
  "$ROOT/tests/ConversationLoopWakePolicyTest.java" \
  "$ROOT/tests/DelegatedSendLeaseTest.java" \
  "$ROOT/tests/ContextPayloadBudgetTest.java" \
  "$ROOT/tests/ScreenItemPriorityPolicyTest.java" \
  "$ROOT/tests/AgentReplayRunner.java"

java -cp "$OUT" com.crewpocket.helper.SendAuthorizationTest
java -cp "$OUT" com.crewpocket.helper.LiveTurnCoordinatorTest
java -cp "$OUT" com.crewpocket.helper.LiveTurnOrderingPolicyTest
java -cp "$OUT" com.crewpocket.helper.LiveHumanTurnBoundaryTest
java -cp "$OUT" com.crewpocket.helper.LiveModelProgressPolicyTest
java -cp "$OUT" com.crewpocket.helper.MediaPlaybackCompletionPolicyTest
java -cp "$OUT" com.crewpocket.helper.MediaGoalUiPolicyTest
java -cp "$OUT" com.crewpocket.helper.MediaTapRecoveryPolicyTest
java -cp "$OUT" com.crewpocket.helper.RefinedMemoryPolicyTest
java -cp "$OUT" com.crewpocket.helper.RefinedMemoryEvidencePolicyTest
java -cp "$OUT" com.crewpocket.helper.RefinedMemoryCorrectionPolicyTest
java -cp "$OUT" com.crewpocket.helper.RefinedMemoryDashboardPolicyTest
java -cp "$OUT" com.crewpocket.helper.RefinedMemoryUseTraceTest
java -cp "$OUT" com.crewpocket.helper.GoogleMapsNavigationStatePolicyTest
java -cp "$OUT" com.crewpocket.helper.TaskRecipeCompletionPolicyTest
java -cp "$OUT" com.crewpocket.helper.ToolCallDispatchLedgerTest
java -cp "$OUT" com.crewpocket.helper.RuntimeToolRoutingTest
java -cp "$OUT" com.crewpocket.helper.ToolIntentRoutingPolicyTest
java -cp "$OUT" com.crewpocket.helper.DeckTurnAdvancePolicyTest
java -cp "$OUT" com.crewpocket.helper.UiChangeSignalTest
java -cp "$OUT" com.crewpocket.helper.RuntimeUiStateTest
java -cp "$OUT" com.crewpocket.helper.LearnedUiScopePolicyTest
java -cp "$OUT" com.crewpocket.helper.SendOnlyPolicyTest
java -cp "$OUT" com.crewpocket.helper.UserActionScopeTest
java -cp "$OUT" com.crewpocket.helper.PendingActionPolicyTest
java -cp "$OUT" com.crewpocket.helper.PendingWaitEventPolicyTest
java -cp "$OUT" com.crewpocket.helper.SessionContextPromptTest
java -cp "$OUT" com.crewpocket.helper.SearchTransactionPolicyTest
java -cp "$OUT" com.crewpocket.helper.SearchResultAutonomyPolicyTest
java -cp "$OUT" com.crewpocket.helper.ScrollDirectionPolicyTest
java -cp "$OUT" com.crewpocket.helper.ActionVerifierV2Test
java -cp "$OUT" com.crewpocket.helper.ActionRecoveryPolicyTest
java -cp "$OUT" com.crewpocket.helper.LocatorConfidencePolicyTest
java -cp "$OUT" com.crewpocket.helper.AgentTaskLifecyclePolicyTest
java -cp "$OUT" com.crewpocket.helper.AgentTaskLifecycleClockTest
java -cp "$OUT" com.crewpocket.helper.AgentRuntimeV2Test
java -cp "$OUT" com.crewpocket.helper.AgentRuntimeRolloutTest
java -cp "$OUT" com.crewpocket.helper.VisionCoordinateMapperTest
java -cp "$OUT" com.crewpocket.helper.VisualTapLeaseTest
java -cp "$OUT" com.crewpocket.helper.ElementReferenceCommandTest
java -cp "$OUT" com.crewpocket.helper.ElementReferenceChoiceTest
java -cp "$OUT" com.crewpocket.helper.ElementReferenceLayoutTest
java -cp "$OUT" com.crewpocket.helper.GoalTaskContinuityPolicyTest
java -cp "$OUT" com.crewpocket.helper.GoalIntentKeyTest
java -cp "$OUT" com.crewpocket.helper.UserRetryAfterUnconfirmedOutcomePolicyTest
java -cp "$OUT" com.crewpocket.helper.InspectorTaskStateTest
java -cp "$OUT" com.crewpocket.helper.InspectorTraceScopeTest
java -cp "$OUT" com.crewpocket.helper.ReflectionLearningPolicyTest
java -cp "$OUT" com.crewpocket.helper.ReflectionRuleEvidenceTest
java -cp "$OUT" com.crewpocket.helper.ExperienceTriggerPolicyTest
java -cp "$OUT" com.crewpocket.helper.ExperiencePolicyEpochTest
java -cp "$OUT" com.crewpocket.helper.ActiveNoiseAdmissionGateTest
java -cp "$OUT" com.crewpocket.helper.TextEntryGoalGuardTest
java -cp "$OUT" com.crewpocket.helper.GoogleMapsSemanticContractTest
java -cp "$OUT" com.crewpocket.helper.AgentTapDiagnosticTest
java -cp "$OUT" com.crewpocket.helper.ModelStepGuidanceTest
java -cp "$OUT" com.crewpocket.helper.ModelRuntimeContractTest
java -cp "$OUT" com.crewpocket.helper.LivePromptTest
java -cp "$OUT" com.crewpocket.helper.ModelScreenPriorityPolicyTest
java -cp "$OUT" com.crewpocket.helper.InformationAnswerFastPathPolicyTest
java -cp "$OUT" com.crewpocket.helper.AppPlaybookRelevanceTest
java -cp "$OUT" com.crewpocket.helper.TaskRecipePolicyTest
java -cp "$OUT" com.crewpocket.helper.VoiceCommandQualityPolicyTest
java -cp "$OUT" com.crewpocket.helper.VoiceExecutionPolicyTest
java -cp "$OUT" com.crewpocket.helper.BubbleLogoStatePolicyTest
java -cp "$OUT" com.crewpocket.helper.CommitGuardTest
java -cp "$OUT" com.crewpocket.helper.AppAutonomyPolicyTest
java -cp "$OUT" com.crewpocket.helper.ConversationLoopRecipeTest
java -cp "$OUT" com.crewpocket.helper.ConversationLoopTakeoverPolicyTest
java -cp "$OUT" com.crewpocket.helper.ConversationLoopWakePolicyTest
java -cp "$OUT" com.crewpocket.helper.DelegatedSendLeaseTest
java -cp "$OUT" com.crewpocket.helper.ContextPayloadBudgetTest
java -cp "$OUT" com.crewpocket.helper.ScreenItemPriorityPolicyTest
java -cp "$OUT" com.crewpocket.helper.AgentReplayRunner "$ROOT/tests/replay"

if command -v python3 >/dev/null 2>&1; then
  python3 -m py_compile "$ROOT/scripts/analyze_context_audit.py"
fi
