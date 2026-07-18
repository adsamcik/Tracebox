use std::env;
use std::fs;
use std::process::ExitCode;

fn main() -> ExitCode {
    let mut args = env::args_os();
    let _program = args.next();
    let Some(path) = args.next() else {
        eprintln!("usage: tbdiag-phase0 <emergency-record>");
        return ExitCode::from(2);
    };
    if args.next().is_some() {
        eprintln!("exactly one path is required");
        return ExitCode::from(2);
    }

    match fs::read(path) {
        Ok(bytes) => match tracebox_phase0::validate_emergency_record(&bytes) {
            Ok(()) => {
                println!("valid emergency record v1");
                ExitCode::SUCCESS
            }
            Err(error) => {
                eprintln!("invalid emergency record: {error:?}");
                ExitCode::from(1)
            }
        },
        Err(error) => {
            eprintln!("read failed: {error}");
            ExitCode::from(1)
        }
    }
}
