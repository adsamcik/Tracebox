#ifndef TRACEBOX_POLICY_TRANSITION_H_
#define TRACEBOX_POLICY_TRANSITION_H_

#include <cstdint>

namespace tracebox {

constexpr uint32_t kPolicyPrepareOperationV1 = 1;
constexpr uint32_t kPolicyCommitOperationV1 = 2;
constexpr uint32_t kPolicyAbortOperationV1 = 3;

struct PolicyStateV1 {
  uint64_t epoch = 0;
  uint64_t deny_mask = UINT64_MAX;
  bool disabled = true;
};

struct PolicyTransitionV1 {
  bool active = false;
  PolicyStateV1 previous;
  PolicyStateV1 target;
  uint32_t finalized_operation = 0;
};

enum class PolicyDecisionV1 {
  kApply,
  kAlreadyApplied,
  kNoOp,
  kReject,
};

inline bool SamePolicyStateV1(const PolicyStateV1& left,
                              const PolicyStateV1& right) {
  return left.epoch == right.epoch &&
         left.deny_mask == right.deny_mask &&
         left.disabled == right.disabled;
}

inline bool SamePolicyTargetV1(const PolicyTransitionV1& transition,
                               const PolicyStateV1& target) {
  return SamePolicyStateV1(transition.target, target);
}

// Losing the handler policy channel never creates a new durable policy epoch.
// The client fences every local capture category at its currently observed
// epoch until Kotlin proves and reinstalls the durable tuple before reconnecting.
inline PolicyStateV1 PolicyParticipantDisconnectFenceV1(
    const PolicyStateV1& current) {
  return PolicyStateV1{current.epoch, UINT64_MAX, true};
}

inline PolicyDecisionV1 DecidePolicyPrepareV1(
    const PolicyTransitionV1& transition,
    const PolicyStateV1& current,
    const PolicyStateV1& target) {
  if (transition.active) {
    return SamePolicyTargetV1(transition, target)
               ? PolicyDecisionV1::kAlreadyApplied
               : PolicyDecisionV1::kReject;
  }
  if (transition.finalized_operation == kPolicyCommitOperationV1 &&
      SamePolicyTargetV1(transition, target) &&
      SamePolicyStateV1(current, transition.target)) {
    return PolicyDecisionV1::kAlreadyApplied;
  }
  return target.epoch > current.epoch ? PolicyDecisionV1::kApply
                                      : PolicyDecisionV1::kReject;
}

inline PolicyDecisionV1 DecideHandlerPolicyCommitV1(
    const PolicyTransitionV1& transition,
    const PolicyStateV1& current,
    uint64_t requested_epoch) {
  if (transition.active) {
    return transition.target.epoch == requested_epoch
               ? PolicyDecisionV1::kApply
               : PolicyDecisionV1::kReject;
  }
  if (transition.finalized_operation == kPolicyCommitOperationV1 &&
      transition.target.epoch == requested_epoch &&
      SamePolicyStateV1(current, transition.target)) {
    return PolicyDecisionV1::kAlreadyApplied;
  }
  // A restarted handler is initialized from the selected durable tuple before
  // it accepts a recovery COMMIT for that same epoch.
  return current.epoch == requested_epoch
             ? PolicyDecisionV1::kAlreadyApplied
             : PolicyDecisionV1::kReject;
}

inline PolicyDecisionV1 DecideClientPolicyCommitV1(
    const PolicyTransitionV1& transition,
    const PolicyStateV1& current,
    const PolicyStateV1& target) {
  if (transition.active) {
    return SamePolicyTargetV1(transition, target)
               ? PolicyDecisionV1::kApply
               : PolicyDecisionV1::kReject;
  }
  if (transition.finalized_operation == kPolicyCommitOperationV1 &&
      SamePolicyTargetV1(transition, target) &&
      SamePolicyStateV1(current, transition.target)) {
    return PolicyDecisionV1::kAlreadyApplied;
  }
  // A restarted client may prove the decision from its already-installed
  // durable tuple even though its in-memory prepared record no longer exists.
  return SamePolicyStateV1(current, target)
             ? PolicyDecisionV1::kAlreadyApplied
             : PolicyDecisionV1::kReject;
}

inline PolicyDecisionV1 DecidePolicyAbortV1(
    const PolicyTransitionV1& transition,
    const PolicyStateV1& current,
    uint64_t requested_epoch) {
  if (transition.active) {
    return transition.target.epoch == requested_epoch
               ? PolicyDecisionV1::kApply
               : PolicyDecisionV1::kReject;
  }
  if (transition.finalized_operation == kPolicyAbortOperationV1 &&
      transition.target.epoch == requested_epoch &&
      SamePolicyStateV1(current, transition.previous)) {
    return PolicyDecisionV1::kAlreadyApplied;
  }
  if (transition.finalized_operation == kPolicyCommitOperationV1 &&
      transition.target.epoch == requested_epoch) {
    return PolicyDecisionV1::kReject;
  }
  return requested_epoch >= current.epoch ? PolicyDecisionV1::kNoOp
                                           : PolicyDecisionV1::kReject;
}

}  // namespace tracebox

#endif  // TRACEBOX_POLICY_TRANSITION_H_
