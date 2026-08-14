# Tracebox host malicious corpus

These cases are intentionally invalid and contain no diagnostic or user data.
`tools/verify/Verify-MaliciousCorpora.ps1` materializes the archive recipes in
a temporary directory and requires every archive and symbol catalog to fail
closed through the production `tbdiag` CLI.

The JSON recipe is committed instead of a binary ZIP so review can see the
exact hostile entry name and payload. Archive cases exercise ZIP validation;
package cases pass ZIP validation and must then fail closed in the generated
record decoder. Symbol cases exercise strict v2 headers, mandatory build
identity, unknown legacy rows, invalid offsets, duplicate build identities,
and ambiguous native aliases.
