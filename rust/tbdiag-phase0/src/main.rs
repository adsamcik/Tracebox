use std::env;
use std::fs;
use std::process::ExitCode;

fn main() -> ExitCode {
    let mut args = env::args_os();
    let _program = args.next();
    let Some(command) = args.next() else {
        eprintln!("usage: tbdiag-phase0 emergency|minidump <path> [seed]");
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
            let seed = args
                .next()
                .map(|value| value.to_string_lossy().into_owned());
            if args.next().is_some() {
                eprintln!("minidump accepts a path and optional seed");
                return ExitCode::from(2);
            }
            match tracebox_phase0::summarize_minidump(&bytes) {
                Ok(summary) => {
                    let raw_seed_matches = seed.as_ref().map_or(0, |value| {
                        tracebox_phase0::count_occurrences(&bytes, value.as_bytes())
                    });
                    print_summary(&summary, raw_seed_matches);
                    ExitCode::SUCCESS
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

fn print_summary(summary: &tracebox_phase0::MinidumpSummary, raw_seed_matches: usize) {
    println!("{{");
    println!("  \"stream_count\": {},", summary.streams.len());
    println!("  \"streams\": [");
    for (index, stream) in summary.streams.iter().enumerate() {
        let comma = if index + 1 == summary.streams.len() {
            ""
        } else {
            ","
        };
        println!(
            "    {{\"type\": {}, \"name\": \"{}\", \"size\": {}}}{}",
            stream.stream_type, stream.name, stream.size, comma
        );
    }
    println!("  ],");
    print_optional("thread_count", summary.thread_count.map(u64::from));
    print_optional("module_count", summary.module_count.map(u64::from));
    print_optional(
        "memory_range_count",
        summary.memory_range_count.map(u64::from),
    );
    print_optional("exception_code", summary.exception_code.map(u64::from));
    print_optional(
        "processor_architecture",
        summary.processor_architecture.map(u64::from),
    );
    println!("  \"raw_seed_matches\": {raw_seed_matches},");
    println!("  \"summary_seed_matches\": 0");
    println!("}}");
}

fn print_optional(name: &str, value: Option<u64>) {
    match value {
        Some(number) => println!("  \"{name}\": {number},"),
        None => println!("  \"{name}\": null,"),
    }
}
