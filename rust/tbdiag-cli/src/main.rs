use std::path::Path;

use tbdiag_cli::{
    RawFrame, RetraceResult, Symbolication, decode_package_records, filter_package_records,
    format_decoded_record, parse_generated_symbol_catalog, read_archive, retrace_generated_catalog,
    symbolize_generated_catalog,
};

fn main() {
    match run(std::env::args().skip(1).collect()) {
        Ok(output) => {
            if !output.is_empty() {
                println!("{output}");
            }
        }
        Err(error) => {
            eprintln!("tbdiag: {error}");
            std::process::exit(2);
        }
    }
}

fn run(args: Vec<String>) -> Result<String, String> {
    match args.as_slice() {
        [command, archive] if command == "inspect" => {
            let parsed = read_archive(Path::new(archive)).map_err(|error| error.to_string())?;
            Ok(parsed
                .entries
                .iter()
                .map(|entry| format!("{} {}", entry.name, entry.bytes.len()))
                .collect::<Vec<_>>()
                .join("\n"))
        }
        [command, archive] if command == "validate" => {
            let parsed = read_archive(Path::new(archive)).map_err(|error| error.to_string())?;
            Ok(format!("valid: {} entries", parsed.entries.len()))
        }
        [command, archive] if command == "decode" => {
            let parsed = read_archive(Path::new(archive)).map_err(|error| error.to_string())?;
            let records = decode_package_records(&parsed).map_err(|error| error.to_string())?;
            Ok(records
                .iter()
                .map(format_decoded_record)
                .collect::<Vec<_>>()
                .join("\n"))
        }
        [command, archive, selector] if command == "filter" => {
            let parsed = read_archive(Path::new(archive)).map_err(|error| error.to_string())?;
            let records = decode_package_records(&parsed).map_err(|error| error.to_string())?;
            let filtered = filter_package_records(&records, selector).map_err(|error| error.to_string())?;
            Ok(filtered
                .iter()
                .map(format_decoded_record)
                .collect::<Vec<_>>()
                .join("\n"))
        }
        [command, catalog, mapping_identity, obfuscated] if command == "retrace" => {
            let entries = load_catalog(Path::new(catalog))?;
            match retrace_generated_catalog(&entries, mapping_identity.to_owned(), obfuscated.to_owned()) {
                RetraceResult::Resolved { obfuscated, original } => Ok(format!("{obfuscated} {original}")),
                RetraceResult::Unresolved { obfuscated } => Err(format!("unresolved R8 frame: {obfuscated}")),
                RetraceResult::Ambiguous { obfuscated, candidates } => {
                    Err(format!("ambiguous R8 frame: {obfuscated}; candidates={candidates:?}"))
                }
                RetraceResult::IdentityMismatch { requested, available } => {
                    Err(format!("R8 mapping identity mismatch: {requested}; catalog identities={available:?}"))
                }
            }
        }
        [command, catalog, module, identity, offset] if command == "symbolize" => {
            let entries = load_catalog(Path::new(catalog))?;
            let raw = RawFrame {
                module: module.to_owned(),
                identity: identity.to_owned(),
                offset: offset.parse().map_err(|_| "offset must be u64")?,
            };
            match symbolize_generated_catalog(&entries, raw) {
                Symbolication::Resolved { raw, symbol } => Ok(format!("{}+0x{:x} {symbol}", raw.module, raw.offset)),
                Symbolication::Unresolved { raw } => Err(format!("unresolved: {}+0x{:x}", raw.module, raw.offset)),
                Symbolication::IdentityMismatch { raw, available } => {
                    Err(format!("identity mismatch: {}+0x{:x}; catalog identities={available:?}", raw.module, raw.offset))
                }
            }
        }
        _ => Err(
            "usage: tbdiag <inspect|validate|decode> ARCHIVE | filter ARCHIVE GENERATED_EVENT | retrace CATALOG.tsv MAPPING_ID OBFUSCATED_FRAME | symbolize CATALOG.tsv MODULE IDENTITY OFFSET"
                .into(),
        ),
    }
}

fn load_catalog(path: &Path) -> Result<tbdiag_cli::GeneratedSymbolCatalog, String> {
    let contents = std::fs::read_to_string(path).map_err(|error| error.to_string())?;
    parse_generated_symbol_catalog(&contents).map_err(|error| error.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn malformed_catalog_line_is_a_hard_error() {
        let root = std::env::current_dir().unwrap().join("build");
        std::fs::create_dir_all(&root).unwrap();
        let path = root.join(format!(
            "tbdiag-catalog-{}-{:?}.tsv",
            std::process::id(),
            std::thread::current().id()
        ));
        std::fs::write(&path, "libx.so\tidentity\tnot-a-number\tsymbol\n").unwrap();
        let error = load_catalog(&path).unwrap_err();
        std::fs::remove_file(path).unwrap();
        assert!(error.contains("malformed catalog line 1"));
    }

    #[test]
    fn command_dispatch_preserves_identity_mismatch_as_a_hard_error() {
        let root = std::env::current_dir().unwrap().join("build");
        std::fs::create_dir_all(&root).unwrap();
        let path = root.join(format!(
            "tbdiag-catalog-{}-{:?}.tsv",
            std::process::id(),
            std::thread::current().id()
        ));
        std::fs::write(&path, "libx.so\tgood\t7\tsymbol\n").unwrap();
        let error = run(vec![
            "symbolize".into(),
            path.display().to_string(),
            "libx.so".into(),
            "wrong".into(),
            "7".into(),
        ])
        .unwrap_err();
        std::fs::remove_file(path).unwrap();
        assert!(error.contains("identity mismatch"));
    }

    #[test]
    fn command_dispatch_decodes_and_filters_generated_package_records() {
        let root = std::env::current_dir().unwrap().join("build");
        std::fs::create_dir_all(&root).unwrap();
        let path = root.join(format!(
            "tbdiag-package-{}-{:?}.tbdiag",
            std::process::id(),
            std::thread::current().id()
        ));
        let mut record = Vec::new();
        for value in [1_u32, 3, 1, 1, 0, 1] {
            record.extend_from_slice(&value.to_be_bytes());
        }
        record.extend_from_slice(&42_u64.to_be_bytes());
        record.extend_from_slice(&7_u32.to_le_bytes());
        record.extend_from_slice(&9_u64.to_le_bytes());
        std::fs::write(&path, stored_zip("records/000001.tbr", &record)).unwrap();

        let decoded = run(vec!["decode".into(), path.display().to_string()]).unwrap();
        assert!(decoded.contains("\"event_type\":\"Breadcrumb\""));
        let filtered = run(vec![
            "filter".into(),
            path.display().to_string(),
            "Breadcrumb".into(),
        ])
        .unwrap();
        std::fs::remove_file(path).unwrap();
        assert!(filtered.contains("\"record\":1"));
    }

    #[test]
    fn command_dispatch_reads_generated_symbol_and_r8_catalog_entries() {
        let root = std::env::current_dir().unwrap().join("build");
        std::fs::create_dir_all(&root).unwrap();
        let path = root.join(format!(
            "tbdiag-generated-catalog-{}-{:?}.tsv",
            std::process::id(),
            std::thread::current().id()
        ));
        std::fs::write(
            &path,
            "# tracebox-symbol-catalog-v1\n\
             native\tlibtracebox.so\tsha256:good\tx86_64\t42\tnative_symbol\n\
             r8\tsha256:mapping\ta.b.c\tdev.tracebox.Original.method\n",
        )
        .unwrap();
        let native = run(vec![
            "symbolize".into(),
            path.display().to_string(),
            "libtracebox.so".into(),
            "sha256:good".into(),
            "42".into(),
        ])
        .unwrap();
        assert_eq!(native, "libtracebox.so+0x2a native_symbol");
        let retraced = run(vec![
            "retrace".into(),
            path.display().to_string(),
            "sha256:mapping".into(),
            "a.b.c".into(),
        ])
        .unwrap();
        std::fs::remove_file(path).unwrap();
        assert_eq!(retraced, "a.b.c dev.tracebox.Original.method");
    }

    fn stored_zip(name: &str, payload: &[u8]) -> Vec<u8> {
        const LOCAL_FILE_HEADER: u32 = 0x0403_4b50;
        const CENTRAL_DIRECTORY_HEADER: u32 = 0x0201_4b50;
        const END_OF_CENTRAL_DIRECTORY: u32 = 0x0605_4b50;
        let mut output = Vec::new();
        let name = name.as_bytes();
        let crc = crc32(payload);
        output.extend_from_slice(&LOCAL_FILE_HEADER.to_le_bytes());
        output.extend_from_slice(&20_u16.to_le_bytes());
        output.extend_from_slice(&0x0800_u16.to_le_bytes());
        output.extend_from_slice(&0_u16.to_le_bytes());
        output.extend_from_slice(&0_u16.to_le_bytes());
        output.extend_from_slice(&0_u16.to_le_bytes());
        output.extend_from_slice(&crc.to_le_bytes());
        output.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        output.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        output.extend_from_slice(&(name.len() as u16).to_le_bytes());
        output.extend_from_slice(&0_u16.to_le_bytes());
        output.extend_from_slice(name);
        output.extend_from_slice(payload);
        let central_offset = output.len();
        output.extend_from_slice(&CENTRAL_DIRECTORY_HEADER.to_le_bytes());
        output.extend_from_slice(&20_u16.to_le_bytes());
        output.extend_from_slice(&20_u16.to_le_bytes());
        output.extend_from_slice(&0x0800_u16.to_le_bytes());
        output.extend_from_slice(&0_u16.to_le_bytes());
        output.extend_from_slice(&0_u16.to_le_bytes());
        output.extend_from_slice(&0_u16.to_le_bytes());
        output.extend_from_slice(&crc.to_le_bytes());
        output.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        output.extend_from_slice(&(payload.len() as u32).to_le_bytes());
        output.extend_from_slice(&(name.len() as u16).to_le_bytes());
        output.extend_from_slice(&0_u16.to_le_bytes());
        output.extend_from_slice(&0_u16.to_le_bytes());
        output.extend_from_slice(&0_u16.to_le_bytes());
        output.extend_from_slice(&0_u16.to_le_bytes());
        output.extend_from_slice(&0_u32.to_le_bytes());
        output.extend_from_slice(&0_u32.to_le_bytes());
        output.extend_from_slice(name);
        let central_size = output.len() - central_offset;
        output.extend_from_slice(&END_OF_CENTRAL_DIRECTORY.to_le_bytes());
        output.extend_from_slice(&0_u16.to_le_bytes());
        output.extend_from_slice(&0_u16.to_le_bytes());
        output.extend_from_slice(&1_u16.to_le_bytes());
        output.extend_from_slice(&1_u16.to_le_bytes());
        output.extend_from_slice(&(central_size as u32).to_le_bytes());
        output.extend_from_slice(&(central_offset as u32).to_le_bytes());
        output.extend_from_slice(&0_u16.to_le_bytes());
        output
    }

    fn crc32(bytes: &[u8]) -> u32 {
        let mut crc = !0_u32;
        for byte in bytes {
            crc ^= u32::from(*byte);
            for _ in 0..8 {
                crc = if crc & 1 != 0 {
                    (crc >> 1) ^ 0xedb8_8320
                } else {
                    crc >> 1
                };
            }
        }
        !crc
    }
}
