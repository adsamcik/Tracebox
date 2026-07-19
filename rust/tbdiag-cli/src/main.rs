use std::path::Path;
use tbdiag_cli::{read_archive, symbolize, RawFrame, SymbolCatalogEntry, Symbolication};
fn main() { if let Err(error) = run(std::env::args().skip(1).collect()) { eprintln!("tbdiag: {error}"); std::process::exit(2); } }
fn run(args: Vec<String>) -> Result<(), String> { match args.as_slice() {
 [command, archive] if command == "inspect" || command == "validate" || command == "decode" => { let parsed=read_archive(Path::new(archive)).map_err(|e| e.to_string())?; if command == "inspect" { for entry in &parsed.entries { println!("{} {}",entry.name,entry.bytes.len()); } } else if command == "decode" { for entry in &parsed.entries { println!("--- {} ---\n{}",entry.name,String::from_utf8_lossy(&entry.bytes)); } } else { println!("valid: {} entries",parsed.entries.len()); } Ok(()) },
 [command, archive, needle] if command == "filter" => { let parsed=read_archive(Path::new(archive)).map_err(|e| e.to_string())?; for entry in parsed.entries.iter().filter(|entry| entry.name.contains(needle)) { println!("{} {}",entry.name,entry.bytes.len()); } Ok(()) },
 [command, catalog, module, identity, offset] if command == "retrace" || command == "symbolize" => { let entries=load_catalog(Path::new(catalog))?; let raw=RawFrame{module:module.to_owned(),identity:identity.to_owned(),offset:offset.parse().map_err(|_|"offset must be u64")?}; match symbolize(&entries,raw) { Symbolication::Resolved{raw,symbol} => { println!("{}+0x{:x} {symbol}",raw.module,raw.offset); Ok(()) }, Symbolication::Unresolved{raw} => Err(format!("unresolved: {}+0x{:x}",raw.module,raw.offset)), Symbolication::IdentityMismatch{raw,available} => Err(format!("identity mismatch: {}+0x{:x}; catalog identities={available:?}",raw.module,raw.offset)) } },
 _ => Err("usage: tbdiag <inspect|validate|decode> ARCHIVE | filter ARCHIVE TEXT | <retrace|symbolize> CATALOG.tsv MODULE IDENTITY OFFSET".into()) } }

fn load_catalog(path: &Path) -> Result<Vec<SymbolCatalogEntry>, String> {
    std::fs::read_to_string(path).map_err(|error| error.to_string())?.lines().enumerate()
        .map(|(index, line)| {
            let mut fields = line.splitn(4, '\t');
            let module = fields.next().unwrap_or_default();
            let identity = fields.next().unwrap_or_default();
            let offset = fields.next().unwrap_or_default();
            let symbol = fields.next().unwrap_or_default();
            if module.is_empty() || identity.is_empty() || symbol.is_empty() {
                return Err(format!("malformed catalog line {}: expected MODULE<TAB>IDENTITY<TAB>OFFSET<TAB>SYMBOL", index + 1));
            }
            let offset = offset.parse().map_err(|_| format!("malformed catalog line {}: offset must be u64", index + 1))?;
            Ok(SymbolCatalogEntry { module: module.to_owned(), identity: identity.to_owned(), offset, symbol: symbol.to_owned() })
        }).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn malformed_catalog_line_is_a_hard_error() {
        let root = std::env::current_dir().unwrap().join("build");
        std::fs::create_dir_all(&root).unwrap();
        let path = root.join(format!("tbdiag-catalog-{}-{:?}.tsv", std::process::id(), std::thread::current().id()));
        std::fs::write(&path, "libx.so\tidentity\tnot-a-number\tsymbol\n").unwrap();
        let error = load_catalog(&path).unwrap_err();
        std::fs::remove_file(path).unwrap();
        assert!(error.contains("malformed catalog line 1"));
    }

    #[test]
    fn command_dispatch_preserves_identity_mismatch_as_a_hard_error() {
        let root = std::env::current_dir().unwrap().join("build");
        std::fs::create_dir_all(&root).unwrap();
        let path = root.join(format!("tbdiag-catalog-{}-{:?}.tsv", std::process::id(), std::thread::current().id()));
        std::fs::write(&path, "libx.so\tgood\t7\tsymbol\n").unwrap();
        let error = run(vec![
            "symbolize".into(), path.display().to_string(), "libx.so".into(), "wrong".into(), "7".into(),
        ]).unwrap_err();
        std::fs::remove_file(path).unwrap();
        assert!(error.contains("identity mismatch"));
    }
}