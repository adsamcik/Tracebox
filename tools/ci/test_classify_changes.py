import unittest
from classify_changes import needs_build


class ChangeClassificationTest(unittest.TestCase):
    def test_documentation_only_skips_compilation(self):
        self.assertFalse(needs_build(["README.md", "docs/releasing.md"]))

    def test_source_mixed_with_documentation_builds(self):
        self.assertTrue(needs_build(["README.md", "android/tracebox/build.gradle.kts"]))

    def test_unknown_and_integrity_inputs_build(self):
        for path in [".github/workflows/ci.yml", "gradle/toolchains.lock.toml", "Cargo.lock",
                     "specs/schema.md", "docs/traceability/work-packages.csv",
                     "docs/generated/schema-reference.md", "docs/adr/0007-open-decision-closure.md", "new-file"]:
            self.assertTrue(needs_build([path]), path)

    def test_empty_change_list_fails_closed(self):
        self.assertTrue(needs_build([]))


if __name__ == "__main__":
    unittest.main()
