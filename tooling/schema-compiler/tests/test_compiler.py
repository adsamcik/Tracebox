import json
import pathlib
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
        field = schema["events"][1]["fields"][2]
        field["name"] = "signed_signal"
        field["semantic_type"] = "i32"
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


if __name__ == "__main__":
    unittest.main()
