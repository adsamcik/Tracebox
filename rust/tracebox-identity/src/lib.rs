//! Internal identifier allocation with persist-before-use enforcement.

#![deny(missing_docs)]

use std::collections::BTreeSet;
use std::io;

use sha2::{Digest, Sha256};

/// Which lifecycle record must be durable before the corresponding identity is usable.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum IdentityKind {
    /// Process lease/census record.
    ProcessInstance,
    /// Segment header.
    OrdinarySegment,
    /// Handler lifecycle journal.
    RawArtifact,
    /// Summary tuple journal.
    Summary,
    /// Summary spool header.
    SummarySpoolSegment,
    /// Snapshot journal.
    Snapshot,
    /// Emergency slot header.
    EmergencyRecord,
    /// Coordinator control journal.
    CoordinatorBootSession,
    /// Process lease before process-state summary.
    ExitCorrelationToken,
}

/// Persistence boundary supplied by Phase 2/3 storage later.
pub trait IdentityJournal {
    /// Durably persists the ID's lifecycle record before the caller receives it.
    ///
    /// # Errors
    ///
    /// Returns the storage failure that prevented durable persistence.
    fn persist(&mut self, kind: IdentityKind, bytes: &[u8]) -> io::Result<()>;
}

/// Allocation failure always prevents the corresponding durable object's creation.
#[derive(Debug)]
pub enum IdentityError {
    /// The platform CSPRNG was unavailable.
    Randomness(getrandom::Error),
    /// The required journal entry did not persist.
    Persistence(io::Error),
    /// A collision was observed and therefore rejected.
    Collision,
    /// Emergency slot sequence was already used for this process instance.
    ReusedEmergencySequence,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
struct RandomId([u8; 32]);

macro_rules! opaque_random_id {
    ($name:ident, $doc:literal) => {
        #[doc = $doc]
        #[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
        pub struct $name(RandomId);

        impl $name {
            /// Returns the stable 256-bit identifier bytes for serialization after allocation.
            #[must_use]
            pub fn as_bytes(self) -> [u8; 32] {
                self.0.0
            }
        }
    };
}

opaque_random_id!(ProcessInstanceId, "A journaled process-instance identity.");
opaque_random_id!(OrdinarySegmentId, "A journaled ordinary-segment identity.");
opaque_random_id!(RawArtifactId, "A journaled raw-artifact identity.");
opaque_random_id!(SummarySpoolSegmentId, "A journaled summary-spool-segment identity.");
opaque_random_id!(SnapshotId, "A journaled snapshot identity.");
opaque_random_id!(CoordinatorBootSessionId, "A journaled coordinator boot-session identity.");

/// A 128-bit process-state exit correlation token allocated through the lease journal.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct ExitCorrelationToken([u8; 16]);

impl ExitCorrelationToken {
    /// Returns the token bytes after durable allocation.
    #[must_use]
    pub fn as_bytes(self) -> [u8; 16] {
        self.0
    }
}

/// A deterministic summary identity whose derivation is journaled before return.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct SummaryId(RandomId);

impl SummaryId {
    /// Derives and persists a summary ID from the frozen summary tuple.
    ///
    /// # Errors
    ///
    /// Returns an error when the required summary journal record cannot be made durable.
    pub fn derive<J: IdentityJournal>(
        raw_artifact_id: RawArtifactId,
        extractor_version: u32,
        schema_fingerprint: &[u8; 32],
        canonical_content_sha256: &[u8; 32],
        journal: &mut J,
    ) -> Result<Self, IdentityError> {
        let mut hasher = Sha256::new();
        hasher.update(b"tracebox-summary-v1");
        hasher.update(raw_artifact_id.as_bytes());
        hasher.update(extractor_version.to_le_bytes());
        hasher.update(schema_fingerprint);
        hasher.update(canonical_content_sha256);
        let id = Self(RandomId(hasher.finalize().into()));
        journal.persist(IdentityKind::Summary, &id.0.0).map_err(IdentityError::Persistence)?;
        Ok(id)
    }

    /// Returns the deterministic ID bytes after durable derivation.
    #[must_use]
    pub fn as_bytes(self) -> [u8; 32] {
        self.0.0
    }
}

/// An emergency identity scoped to an existing persisted process instance.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct EmergencyRecordId {
    process_instance: ProcessInstanceId,
    slot_sequence: u64,
}

impl EmergencyRecordId {
    /// Returns the already-persisted process identity that scopes this record.
    #[must_use]
    pub fn process_instance(self) -> ProcessInstanceId {
        self.process_instance
    }

    /// Returns the never-reused slot sequence within its process instance.
    #[must_use]
    pub fn slot_sequence(self) -> u64 {
        self.slot_sequence
    }
}

/// Allocates typed identities and rejects reuse before their first durable use.
#[derive(Default)]
pub struct IdentityAllocator {
    random_ids: BTreeSet<RandomId>,
    exit_tokens: BTreeSet<ExitCorrelationToken>,
    emergency_slots: BTreeSet<(RandomId, u64)>,
}

impl IdentityAllocator {
    fn random<J: IdentityJournal>(&mut self, kind: IdentityKind, journal: &mut J) -> Result<RandomId, IdentityError> {
        let mut bytes = [0_u8; 32];
        getrandom::fill(&mut bytes).map_err(IdentityError::Randomness)?;
        let id = RandomId(bytes);
        if self.random_ids.contains(&id) {
            return Err(IdentityError::Collision);
        }
        journal.persist(kind, &id.0).map_err(IdentityError::Persistence)?;
        self.random_ids.insert(id);
        Ok(id)
    }

    /// Allocates and journals a process instance before returning it.
    ///
    /// # Errors
    ///
    /// Returns an error when randomness, collision checks, or durable journaling fails.
    pub fn process_instance<J: IdentityJournal>(&mut self, journal: &mut J) -> Result<ProcessInstanceId, IdentityError> {
        self.random(IdentityKind::ProcessInstance, journal).map(ProcessInstanceId)
    }

    /// Allocates and journals an ordinary segment before returning it.
    ///
    /// # Errors
    ///
    /// Returns an error when randomness, collision checks, or durable journaling fails.
    pub fn ordinary_segment<J: IdentityJournal>(&mut self, journal: &mut J) -> Result<OrdinarySegmentId, IdentityError> {
        self.random(IdentityKind::OrdinarySegment, journal).map(OrdinarySegmentId)
    }

    /// Allocates and journals a raw artifact before returning it.
    ///
    /// # Errors
    ///
    /// Returns an error when randomness, collision checks, or durable journaling fails.
    pub fn raw_artifact<J: IdentityJournal>(&mut self, journal: &mut J) -> Result<RawArtifactId, IdentityError> {
        self.random(IdentityKind::RawArtifact, journal).map(RawArtifactId)
    }

    /// Allocates and journals a summary spool segment before returning it.
    ///
    /// # Errors
    ///
    /// Returns an error when randomness, collision checks, or durable journaling fails.
    pub fn summary_spool_segment<J: IdentityJournal>(&mut self, journal: &mut J) -> Result<SummarySpoolSegmentId, IdentityError> {
        self.random(IdentityKind::SummarySpoolSegment, journal).map(SummarySpoolSegmentId)
    }

    /// Allocates and journals a snapshot before returning it.
    ///
    /// # Errors
    ///
    /// Returns an error when randomness, collision checks, or durable journaling fails.
    pub fn snapshot<J: IdentityJournal>(&mut self, journal: &mut J) -> Result<SnapshotId, IdentityError> {
        self.random(IdentityKind::Snapshot, journal).map(SnapshotId)
    }

    /// Allocates and journals a coordinator boot session before returning it.
    ///
    /// # Errors
    ///
    /// Returns an error when randomness, collision checks, or durable journaling fails.
    pub fn coordinator_boot_session<J: IdentityJournal>(&mut self, journal: &mut J) -> Result<CoordinatorBootSessionId, IdentityError> {
        self.random(IdentityKind::CoordinatorBootSession, journal).map(CoordinatorBootSessionId)
    }

    /// Allocates and journals an exit correlation token before returning it.
    ///
    /// # Errors
    ///
    /// Returns an error when randomness, collision checks, or durable journaling fails.
    pub fn exit_token<J: IdentityJournal>(&mut self, journal: &mut J) -> Result<ExitCorrelationToken, IdentityError> {
        let mut bytes = [0_u8; 16];
        getrandom::fill(&mut bytes).map_err(IdentityError::Randomness)?;
        let token = ExitCorrelationToken(bytes);
        if self.exit_tokens.contains(&token) {
            return Err(IdentityError::Collision);
        }
        journal.persist(IdentityKind::ExitCorrelationToken, &bytes).map_err(IdentityError::Persistence)?;
        self.exit_tokens.insert(token);
        Ok(token)
    }

    /// Persists an emergency slot identity and rejects sequence reuse.
    ///
    /// # Errors
    ///
    /// Returns an error when the sequence is reused or its required journal record cannot persist.
    pub fn emergency_record<J: IdentityJournal>(
        &mut self,
        process_instance: ProcessInstanceId,
        slot_sequence: u64,
        journal: &mut J,
    ) -> Result<EmergencyRecordId, IdentityError> {
        if !self.emergency_slots.insert((process_instance.0, slot_sequence)) {
            return Err(IdentityError::ReusedEmergencySequence);
        }
        let mut bytes = Vec::with_capacity(40);
        bytes.extend_from_slice(&process_instance.as_bytes());
        bytes.extend_from_slice(&slot_sequence.to_le_bytes());
        if let Err(error) = journal.persist(IdentityKind::EmergencyRecord, &bytes) {
            self.emergency_slots.remove(&(process_instance.0, slot_sequence));
            return Err(IdentityError::Persistence(error));
        }
        Ok(EmergencyRecordId { process_instance, slot_sequence })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Default)]
    struct Journal(Vec<(IdentityKind, Vec<u8>)>);
    impl IdentityJournal for Journal {
        fn persist(&mut self, kind: IdentityKind, bytes: &[u8]) -> io::Result<()> {
            self.0.push((kind, bytes.to_vec()));
            Ok(())
        }
    }

    struct FailingJournal;
    impl IdentityJournal for FailingJournal {
        fn persist(&mut self, _: IdentityKind, _: &[u8]) -> io::Result<()> {
            Err(io::Error::other("not durable"))
        }
    }

    #[test]
    fn typed_random_ids_are_journaled_before_return_and_never_reused() {
        let mut allocator = IdentityAllocator::default();
        let mut journal = Journal::default();
        let process = allocator.process_instance(&mut journal).unwrap();
        let segment = allocator.ordinary_segment(&mut journal).unwrap();
        let raw = allocator.raw_artifact(&mut journal).unwrap();
        let snapshot = allocator.snapshot(&mut journal).unwrap();
        let boot = allocator.coordinator_boot_session(&mut journal).unwrap();
        assert_eq!(journal.0.len(), 5);
        let ids = [process.as_bytes(), segment.as_bytes(), raw.as_bytes(), snapshot.as_bytes(), boot.as_bytes()];
        assert!(ids.windows(2).all(|pair| pair[0] != pair[1]));
    }

    #[test]
    fn persistence_failure_never_yields_a_usable_process_identity() {
        let mut allocator = IdentityAllocator::default();
        let mut journal = FailingJournal;
        assert!(matches!(allocator.process_instance(&mut journal), Err(IdentityError::Persistence(_))));
    }

    #[test]
    fn summary_is_deterministic_journaled_and_not_randomly_allocatable() {
        let mut allocator = IdentityAllocator::default();
        let mut journal = Journal::default();
        let raw = allocator.raw_artifact(&mut journal).unwrap();
        let first = SummaryId::derive(raw, 1, &[1; 32], &[2; 32], &mut journal).unwrap();
        let second = SummaryId::derive(raw, 1, &[1; 32], &[2; 32], &mut journal).unwrap();
        assert_eq!(first, second);
        assert_eq!(journal.0[1].0, IdentityKind::Summary);
        assert!(journal.0.iter().all(|entry| entry.0 != IdentityKind::Summary || entry.1 == first.as_bytes()));
    }

    #[test]
    fn emergency_sequence_is_not_reused_after_durable_allocation() {
        let mut allocator = IdentityAllocator::default();
        let mut journal = Journal::default();
        let process = allocator.process_instance(&mut journal).unwrap();
        allocator.emergency_record(process, 1, &mut journal).unwrap();
        assert!(matches!(allocator.emergency_record(process, 1, &mut journal), Err(IdentityError::ReusedEmergencySequence)));
    }
}
