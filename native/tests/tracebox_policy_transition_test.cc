#include "tracebox/policy_transition.h"

#include <cassert>
#include <cstdint>

namespace {

using tracebox::PolicyDecisionV1;
using tracebox::PolicyStateV1;
using tracebox::PolicyTransitionV1;

constexpr PolicyStateV1 State(uint64_t epoch,
                              bool disabled,
                              uint64_t deny_mask) {
  return PolicyStateV1{epoch, deny_mask, disabled};
}

void TestPrepareIsIdempotentAndRejectsConflicts() {
  const PolicyStateV1 previous = State(4, false, 8);
  const PolicyStateV1 target = State(5, false, 12);
  PolicyTransitionV1 transition;

  assert(tracebox::DecidePolicyPrepareV1(
             transition, previous, target) == PolicyDecisionV1::kApply);

  transition.active = true;
  transition.previous = previous;
  transition.target = target;
  assert(tracebox::DecidePolicyPrepareV1(
             transition, State(5, true, UINT64_MAX), target) ==
         PolicyDecisionV1::kAlreadyApplied);
  assert(tracebox::DecidePolicyPrepareV1(
             transition, State(5, true, UINT64_MAX), State(5, false, 1)) ==
         PolicyDecisionV1::kReject);

  transition.active = false;
  transition.finalized_operation =
      tracebox::kPolicyCommitOperationV1;
  assert(tracebox::DecidePolicyPrepareV1(
             transition, target, target) ==
         PolicyDecisionV1::kAlreadyApplied);
  assert(tracebox::DecidePolicyPrepareV1(
             PolicyTransitionV1{}, target, target) ==
         PolicyDecisionV1::kReject);
}

void TestParticipantDisconnectFailsClosedWithoutInventingAnEpoch() {
  const PolicyStateV1 current = State(7, false, 2);
  const PolicyStateV1 fenced =
      tracebox::PolicyParticipantDisconnectFenceV1(current);
  assert(fenced.epoch == current.epoch);
  assert(fenced.disabled);
  assert(fenced.deny_mask == UINT64_MAX);

  const PolicyStateV1 already_disabled =
      tracebox::PolicyParticipantDisconnectFenceV1(
          State(9, true, UINT64_MAX));
  assert(already_disabled.epoch == 9);
  assert(already_disabled.disabled);
  assert(already_disabled.deny_mask == UINT64_MAX);
}

void TestCommitSurvivesAcknowledgementLossAndRestart() {
  const PolicyStateV1 previous = State(10, false, 4);
  const PolicyStateV1 target = State(11, false, 6);
  PolicyTransitionV1 transition{
      true,
      previous,
      target,
      0,
  };
  assert(tracebox::DecideHandlerPolicyCommitV1(
             transition, State(11, true, UINT64_MAX), target.epoch) ==
         PolicyDecisionV1::kApply);
  assert(tracebox::DecideClientPolicyCommitV1(
             transition, State(11, true, UINT64_MAX), target) ==
         PolicyDecisionV1::kApply);

  transition.active = false;
  transition.finalized_operation =
      tracebox::kPolicyCommitOperationV1;
  assert(tracebox::DecideHandlerPolicyCommitV1(
             transition, target, target.epoch) ==
         PolicyDecisionV1::kAlreadyApplied);
  assert(tracebox::DecideClientPolicyCommitV1(
             transition, target, target) ==
         PolicyDecisionV1::kAlreadyApplied);

  // A restarted participant has no in-memory prepared tuple, but installation
  // of the exact durable target is enough to acknowledge the same epoch.
  assert(tracebox::DecideHandlerPolicyCommitV1(
             PolicyTransitionV1{}, target, target.epoch) ==
         PolicyDecisionV1::kAlreadyApplied);
  assert(tracebox::DecideClientPolicyCommitV1(
             PolicyTransitionV1{}, target, target) ==
         PolicyDecisionV1::kAlreadyApplied);
  assert(tracebox::DecideClientPolicyCommitV1(
             PolicyTransitionV1{}, State(11, false, 7), target) ==
         PolicyDecisionV1::kReject);
}

void TestAbortIsIdempotentButCannotUndoCommit() {
  const PolicyStateV1 previous = State(20, false, 2);
  const PolicyStateV1 target = State(21, true, UINT64_MAX);
  PolicyTransitionV1 transition{
      true,
      previous,
      target,
      0,
  };
  assert(tracebox::DecidePolicyAbortV1(
             transition, State(21, true, UINT64_MAX), target.epoch) ==
         PolicyDecisionV1::kApply);

  transition.active = false;
  transition.finalized_operation =
      tracebox::kPolicyAbortOperationV1;
  assert(tracebox::DecidePolicyAbortV1(
             transition, previous, target.epoch) ==
         PolicyDecisionV1::kAlreadyApplied);

  transition.finalized_operation =
      tracebox::kPolicyCommitOperationV1;
  assert(tracebox::DecidePolicyAbortV1(
             transition, target, target.epoch) ==
         PolicyDecisionV1::kReject);

  assert(tracebox::DecidePolicyAbortV1(
             PolicyTransitionV1{}, previous, target.epoch) ==
         PolicyDecisionV1::kNoOp);
  assert(tracebox::DecidePolicyAbortV1(
             PolicyTransitionV1{}, target, previous.epoch) ==
         PolicyDecisionV1::kReject);
}

}  // namespace

int main() {
  TestPrepareIsIdempotentAndRejectsConflicts();
  TestParticipantDisconnectFailsClosedWithoutInventingAnEpoch();
  TestCommitSurvivesAcknowledgementLossAndRestart();
  TestAbortIsIdempotentButCannotUndoCommit();
  return 0;
}
