pub const EMERGENCY_RECORD_SIZE: usize = 256;
pub const EMERGENCY_MAGIC: &[u8; 8] = b"TBEMERG1";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EmergencyRecordError {
    InvalidSize,
    InvalidMagic,
    InvalidVersion,
    Incomplete,
}

/// Validates the fixed structural fields of one emergency record.
///
/// # Errors
///
/// Returns the first structural error found. It does not accept partial records.
pub fn validate_emergency_record(record: &[u8]) -> Result<(), EmergencyRecordError> {
    if record.len() != EMERGENCY_RECORD_SIZE {
        return Err(EmergencyRecordError::InvalidSize);
    }
    if &record[0..8] != EMERGENCY_MAGIC {
        return Err(EmergencyRecordError::InvalidMagic);
    }
    if u32::from_le_bytes([record[8], record[9], record[10], record[11]]) != 1 {
        return Err(EmergencyRecordError::InvalidVersion);
    }
    let marker = u64::from_le_bytes([
        record[248],
        record[249],
        record[250],
        record[251],
        record[252],
        record[253],
        record[254],
        record[255],
    ]);
    if marker != 0x5442_454d_434f_4d50 {
        return Err(EmergencyRecordError::Incomplete);
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn validates_complete_record() {
        let mut record = [0_u8; EMERGENCY_RECORD_SIZE];
        record[0..8].copy_from_slice(EMERGENCY_MAGIC);
        record[8..12].copy_from_slice(&1_u32.to_le_bytes());
        record[248..256].copy_from_slice(&0x5442_454d_434f_4d50_u64.to_le_bytes());
        assert_eq!(validate_emergency_record(&record), Ok(()));
    }

    #[test]
    fn rejects_truncated_record() {
        assert_eq!(
            validate_emergency_record(&[0_u8; 255]),
            Err(EmergencyRecordError::InvalidSize)
        );
    }
}
