use std::env;
use std::fs;
use std::process::ExitCode;

fn main() -> ExitCode {
    let mut args = env::args_os();
    let _program = args.next();
    let Some(command) = args.next() else {
        eprintln!("usage: tbdiag-phase0 emergency <path> | minidump <path> <seed> <identity-hex>");
        return ExitCode::from(2);
    };
    let Some(path) = args.next() else {
        eprintln!("a path is required");
        return ExitCode::from(2);
    };

    match fs::read(path) {
        Ok(bytes) if command == "emergency" => {
            if args.next().is_some() {
                eprintln!("emergency accepts one path");
                return ExitCode::from(2);
            }
            match tracebox_phase0::validate_emergency_record(&bytes) {
                Ok(()) => {
                    println!("valid emergency record v1");
                    ExitCode::SUCCESS
                }
                Err(error) => {
                    eprintln!("invalid emergency record: {error:?}");
                    ExitCode::from(1)
                }
            }
        }
        Ok(bytes) if command == "minidump" => {
            let Some(seed) = args.next() else {
                eprintln!("minidump requires a non-empty seed");
                return ExitCode::from(2);
            };
            let seed = seed.to_string_lossy().into_owned();
            let Some(identity_hex) = args.next() else {
                eprintln!("minidump requires one established 256-bit identity");
                return ExitCode::from(2);
            };
            if args.next().is_some() {
                eprintln!("minidump accepts one path, seed, and identity");
                return ExitCode::from(2);
            }
            let Some(identity) = decode_identity_hex(&identity_hex.to_string_lossy()) else {
                eprintln!("identity must be exactly 64 hexadecimal characters");
                return ExitCode::from(2);
            };
            match tracebox_phase0::summarize_minidump(&bytes) {
                Ok(summary) => {
                    let serialized = tracebox_phase0::serialize_structural_summary(&summary);
                    match tracebox_phase0::scan_privacy(
                        &bytes,
                        serialized.as_bytes(),
                        seed.as_bytes(),
                        &identity,
                    ) {
                        Ok(scan) => {
                            print_summary(&serialized, scan);
                            ExitCode::SUCCESS
                        }
                        Err(error) => {
                            eprintln!("privacy scan failed: {error:?}");
                            ExitCode::from(1)
                        }
                    }
                }
                Err(error) => {
                    eprintln!("invalid minidump: {error:?}");
                    ExitCode::from(1)
                }
            }
        }
        Ok(_) => {
            eprintln!("unknown command");
            ExitCode::from(2)
        }
        Err(error) => {
            eprintln!("read failed: {error}");
            ExitCode::from(1)
        }
    }
}

fn print_summary(serialized: &str, scan: tracebox_phase0::PrivacyScanResult) {
    let prefix = serialized
        .strip_suffix('}')
        .expect("structural summary is a JSON object");
    print!("{prefix}");
    println!(",");
    println!("  \"raw_seed_matches\": {},", scan.raw_seed_matches);
    println!("  \"summary_seed_matches\": {},", scan.summary_seed_matches);
    println!("  \"raw_identity_matches\": {},", scan.raw_identity_matches);
    println!(
        "  \"summary_identity_matches\": {},",
        scan.summary_identity_matches
    );
    println!(
        "  \"identity_encodings_scanned\": {}",
        scan.identity_encodings_scanned
    );
    println!("}}");
}

fn decode_identity_hex(value: &str) -> Option<[u8; tracebox_phase0::PROCESS_INSTANCE_ID_SIZE]> {
    if value.len() != tracebox_phase0::PROCESS_INSTANCE_ID_SIZE * 2 {
        return None;
    }
    let mut identity = [0_u8; tracebox_phase0::PROCESS_INSTANCE_ID_SIZE];
    for (index, destination) in identity.iter_mut().enumerate() {
        let start = index * 2;
        *destination = u8::from_str_radix(&value[start..start + 2], 16).ok()?;
    }
    Some(identity)
}
