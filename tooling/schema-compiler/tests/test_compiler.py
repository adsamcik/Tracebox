import json
import pathlib
import re
import shutil
import subprocess
import sys
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[3]
COMPILER = ROOT / "tooling" / "schema-compiler" / "compile_schema.py"
GOLDEN = ROOT / "tooling" / "schema-compiler" / "tests" / "golden"


class SchemaCompilerTests(unittest.TestCase):
    def setUp(self):
        self.out = ROOT / "tooling" / "schema-compiler" / "tests" / ".generated"
        shutil.rmtree(self.out, ignore_errors=True)

    def tearDown(self):
        shutil.rmtree(self.out, ignore_errors=True)

    def compile(self, schema):
        candidate = self.out / "schema.json"
        candidate.parent.mkdir(parents=True, exist_ok=True)
        candidate.write_text(json.dumps(schema), encoding="utf-8")
        return subprocess.run(
            [sys.executable, str(COMPILER), "--schema", str(candidate), "--out", str(self.out)],
            text=True, capture_output=True, check=False,
        )

    def test_generated_files_match_goldens(self):
        result = subprocess.run(
            [sys.executable, str(COMPILER), "--out", str(self.out)],
            text=True, capture_output=True, check=False,
        )
        self.assertEqual(0, result.returncode, result.stderr)
        for golden in GOLDEN.rglob("*"):
            if golden.is_file():
                self.assertEqual(golden.read_bytes(), (self.out / golden.relative_to(GOLDEN)).read_bytes())

    def test_field_name_and_type_drive_every_contract_target(self):
        schema = json.loads((ROOT / "schema" / "events.json").read_text(encoding="utf-8"))
        schema["events"].append({
            "id": 12,
            "name": "SignedProbe",
            "category": "compatibility_probe",
            "retention": "ordinary",
            "package_visibility": "standard",
            "direct_boot_eligible": False,
            "fields": [{
                "id": 1,
                "name": "signed_signal",
                "privacy": "C0",
                "semantic_type": "i32",
                "max_encoded_size": 5,
                "transform": "none",
            }],
        })
        result = self.compile(schema)
        self.assertEqual(0, result.returncode, result.stderr)
        targets = [
            "android/tracebox-api/src/main/kotlin/dev/tracebox/api/generated/GeneratedSchema.kt",
            "native/include/tracebox/generated_events.h",
            "rust/tracebox-sys/src/generated.rs",
            "schema/generated/tracebox_records.proto",
        ]
        rendered = [(self.out / target).read_text(encoding="utf-8") for target in targets]
        self.assertTrue(all("signed_signal" in content for content in rendered))
        self.assertIn("val signed_signal: Int", rendered[0])
        self.assertIn("int32_t signed_signal", rendered[1])
        self.assertIn("pub signed_signal: i32", rendered[2])
        self.assertIn("int32 signed_signal", rendered[3])

    def test_prohibited_type_fails_at_generation(self):
        schema = json.loads((ROOT / "schema" / "events.json").read_text(encoding="utf-8"))
        schema["events"][0]["fields"][0]["semantic_type"] = "prohibited_secret"
        self.assertNotEqual(0, self.compile(schema).returncode)

    def test_unknown_custom_field_fails_at_generation(self):
        schema = json.loads((ROOT / "schema" / "events.json").read_text(encoding="utf-8"))
        schema["events"][0]["fields"][0]["unexpected"] = "implicit C2 is forbidden in release schema"
        self.assertNotEqual(0, self.compile(schema).returncode)

    def test_reused_id_fails_at_generation(self):
        schema = json.loads((ROOT / "schema" / "events.json").read_text(encoding="utf-8"))
        schema["events"][1]["id"] = schema["events"][0]["id"]
        self.assertNotEqual(0, self.compile(schema).returncode)

    def test_released_event_prefix_is_immutable(self):
        source = json.loads((ROOT / "schema" / "events.json").read_text(encoding="utf-8"))
        mutations = []

        renamed = json.loads(json.dumps(source))
        renamed["events"][0]["name"] = "RenamedStructuralSummary"
        mutations.append(renamed)

        removed = json.loads(json.dumps(source))
        removed["events"].pop(4)
        mutations.append(removed)

        reordered = json.loads(json.dumps(source))
        reordered["events"][0], reordered["events"][1] = reordered["events"][1], reordered["events"][0]
        mutations.append(reordered)

        field_changed = json.loads(json.dumps(source))
        field_changed["events"][2]["fields"][0]["privacy"] = "C2"
        mutations.append(field_changed)

        field_appended = json.loads(json.dumps(source))
        field_appended["events"][3]["fields"].append({
            "id": 3,
            "name": "new_field",
            "privacy": "C0",
            "semantic_type": "u32",
            "max_encoded_size": 5,
            "transform": "none",
        })
        mutations.append(field_appended)

        for mutation in mutations:
            with self.subTest(mutation=mutation):
                self.assertNotEqual(0, self.compile(mutation).returncode)

    def test_released_reserved_ids_cannot_be_removed(self):
        schema = json.loads((ROOT / "schema" / "events.json").read_text(encoding="utf-8"))
        schema["reserved_event_ids"] = []
        self.assertNotEqual(0, self.compile(schema).returncode)

    def test_new_event_must_be_appended_and_is_compatible(self):
        schema = json.loads((ROOT / "schema" / "events.json").read_text(encoding="utf-8"))
        schema["events"].append({
            "id": 12,
            "name": "CompatibilityProbe",
            "category": "compatibility_probe",
            "retention": "ordinary",
            "package_visibility": "standard",
            "direct_boot_eligible": False,
            "fields": [{
                "id": 1,
                "name": "code",
                "privacy": "C0",
                "semantic_type": "u32",
                "max_encoded_size": 5,
                "transform": "none",
            }],
        })
        result = self.compile(schema)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_direct_boot_fingerprint_ignores_appended_events_and_rejects_projection_changes(self):
        schema = json.loads((ROOT / "schema" / "events.json").read_text(encoding="utf-8"))
        baseline = self.compile(schema)
        self.assertEqual(0, baseline.returncode, baseline.stderr)
        generated = self.out / (
            "android/tracebox-directboot/src/main/kotlin/dev/tracebox/directboot/"
            "GeneratedDirectBootSchema.kt"
        )
        baseline_fingerprint = self.direct_boot_fingerprint(generated.read_text(encoding="utf-8"))

        schema["events"].append({
            "id": 12,
            "name": "UnrelatedProbe",
            "category": "compatibility_probe",
            "retention": "ordinary",
            "package_visibility": "standard",
            "direct_boot_eligible": False,
            "fields": [{
                "id": 1,
                "name": "unrelated_code",
                "privacy": "C0",
                "semantic_type": "u32",
                "max_encoded_size": 5,
                "transform": "none",
            }],
        })
        unrelated = self.compile(schema)
        self.assertEqual(0, unrelated.returncode, unrelated.stderr)
        self.assertEqual(
            baseline_fingerprint,
            self.direct_boot_fingerprint(generated.read_text(encoding="utf-8")),
        )

        schema["events"][1]["fields"][0]["name"] = "direct_boot_slot"
        direct_boot_changed = self.compile(schema)
        self.assertNotEqual(0, direct_boot_changed.returncode)

    @staticmethod
    def direct_boot_fingerprint(generated):
        match = re.search(
            r"object GeneratedDirectBootSchemaFingerprint \{\s+"
            r'const val HEX: String = "([0-9a-f]{64})"',
            generated,
        )
        if match is None:
            raise AssertionError("generated Direct Boot fingerprint is missing")
        return match.group(1)


if __name__ == "__main__":
    unittest.main()
