//! Internal identifier allocation with persist-before-use enforcement.

#![deny(missing_docs)]

use std::collections::BTreeSet;
use std::io;

use sha2::{Digest, Sha256};

/// A 256-bit internal random identifier.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct RandomId(pub [u8; 32]);

/// A 128-bit process-state exit correlation token.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub struct ExitCorrelationToken(pub [u8; 16]);

/// An emergency identity scoped to an existing process instance.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct EmergencyRecordId {
    /// Existing persisted process instance.
    pub process_instance: RandomId,
    /// Monotonic, never-reused sequence within that instance.
    pub slot_sequence: u64,
}

/// Which persisted lifecycle record must precede an identifier's first use.
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

/// Generates identities and remembers issued values to prevent accidental reuse.
#[derive(Default)]
pub struct IdentityAllocator {
    random_ids: BTreeSet<RandomId>,
    exit_tokens: BTreeSet<ExitCorrelationToken>,
    emergency_slots: BTreeSet<(RandomId, u64)>,
}

impl IdentityAllocator {
    /// Allocates and persists a random 256-bit ID before returning it.
    ///
    /// # Errors
    ///
    /// Returns when CSPRNG generation, journal persistence, or collision detection fails.
    pub fn random<J: IdentityJournal>(
        &mut self,
        kind: IdentityKind,
        journal: &mut J,
    ) -> Result<RandomId, IdentityError> {
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

    /// Allocates and persists a random 128-bit exit token before it is encoded.
    ///
    /// # Errors
    ///
    /// Returns when CSPRNG generation or journal persistence fails.
    pub fn exit_token<J: IdentityJournal>(
        &mut self,
        journal: &mut J,
    ) -> Result<ExitCorrelationToken, IdentityError> {
        let mut bytes = [0_u8; 16];
        getrandom::fill(&mut bytes).map_err(IdentityError::Randomness)?;
        let token = ExitCorrelationToken(bytes);
        if self.exit_tokens.contains(&token) {
            return Err(IdentityError::Collision);
        }
        journal
            .persist(IdentityKind::ExitCorrelationToken, &bytes)
            .map_err(IdentityError::Persistence)?;
        self.exit_tokens.insert(token);
        Ok(token)
    }

    /// Derives the deliberately deduplicated summary ID from its frozen tuple.
    #[must_use]
    pub fn summary_id(
        raw_artifact: RandomId,
        extractor_version: u32,
        schema_fingerprint: &[u8; 32],
        canonical_content_sha256: &[u8; 32],
    ) -> RandomId {
        let mut hasher = Sha256::new();
        hasher.update(b"tracebox-summary-v1");
        hasher.update(raw_artifact.0);
        hasher.update(extractor_version.to_le_bytes());
        hasher.update(schema_fingerprint);
        hasher.update(canonical_content_sha256);
        RandomId(hasher.finalize().into())
    }

    /// Persists the exact summary ID before a later spool append.
    ///
    /// # Errors
    ///
    /// Returns when its required summary journal entry cannot be persisted.
    pub fn persist_summary<J: IdentityJournal>(
        &mut self,
        id: RandomId,
        journal: &mut J,
    ) -> Result<(), IdentityError> {
        journal.persist(IdentityKind::Summary, &id.0).map_err(IdentityError::Persistence)
    }

    /// Persists an emergency slot identity and rejects sequence reuse.
    ///
    /// # Errors
    ///
    /// Returns when the slot sequence was already used or the slot header cannot persist.
    pub fn emergency_record<J: IdentityJournal>(
        &mut self,
        process_instance: RandomId,
        slot_sequence: u64,
        journal: &mut J,
    ) -> Result<EmergencyRecordId, IdentityError> {
        if !self.emergency_slots.insert((process_instance, slot_sequence)) {
            return Err(IdentityError::ReusedEmergencySequence);
        }
        let mut bytes = Vec::with_capacity(40);
        bytes.extend_from_slice(&process_instance.0);
        bytes.extend_from_slice(&slot_sequence.to_le_bytes());
        journal.persist(IdentityKind::EmergencyRecord, &bytes).map_err(IdentityError::Persistence)?;
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

    #[test]
    fn random_ids_are_persisted_before_return_and_unique() {
        let mut allocator = IdentityAllocator::default();
        let mut journal = Journal::default();
        let kinds = [
            IdentityKind::ProcessInstance,
            IdentityKind::OrdinarySegment,
            IdentityKind::RawArtifact,
            IdentityKind::SummarySpoolSegment,
            IdentityKind::Snapshot,
            IdentityKind::CoordinatorBootSession,
        ];
        let ids: Vec<_> = kinds
            .into_iter()
            .map(|kind| allocator.random(kind, &mut journal).unwrap())
            .collect();
        assert!(ids.windows(2).all(|pair| pair[0] != pair[1]));
        let first_token = allocator.exit_token(&mut journal).unwrap();
        let second_token = allocator.exit_token(&mut journal).unwrap();
        assert_ne!(first_token, second_token);
        assert_eq!(journal.0.len(), 8);
    }

    #[test]
    fn summary_is_deterministic_and_emergency_sequence_is_not_reused() {
        let mut allocator = IdentityAllocator::default();
        let mut journal = Journal::default();
        let process = allocator.random(IdentityKind::ProcessInstance, &mut journal).unwrap();
        let summary_a = IdentityAllocator::summary_id(process, 1, &[1; 32], &[2; 32]);
        let summary_b = IdentityAllocator::summary_id(process, 1, &[1; 32], &[2; 32]);
        assert_eq!(summary_a, summary_b);
        allocator.persist_summary(summary_a, &mut journal).unwrap();
        allocator.emergency_record(process, 1, &mut journal).unwrap();
        assert!(matches!(
            allocator.emergency_record(process, 1, &mut journal),
            Err(IdentityError::ReusedEmergencySequence)
        ));
    }
}
