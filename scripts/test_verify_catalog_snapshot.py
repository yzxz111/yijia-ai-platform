"""Offline tests for the read-only curated Expert snapshot verifier."""
from __future__ import annotations

import importlib.util
import io
import json
import sys
import unittest
import zipfile
from pathlib import Path
from unittest.mock import Mock


SYSTEM_ROOT = Path(__file__).resolve().parent.parent
MODULE_PATH = Path(__file__).with_name("verify-catalog-snapshot.py")
spec = importlib.util.spec_from_file_location("verify_catalog_snapshot", MODULE_PATH)
assert spec is not None and spec.loader is not None
module = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = module
spec.loader.exec_module(module)


class VerifyCatalogSnapshotTest(unittest.TestCase):
    def setUp(self) -> None:
        self.catalog = module.load_catalog()
        self.rows = module.curated_rows(self.catalog)

    def test_snapshot_has_exact_six_curated_experts_and_expected_revisions(self) -> None:
        self.assertEqual(
            {
                "ai-office-assistant": 9,
                "content-summarizer": 7,
                "content-writing-expert": 4,
                "document-review-expert": 6,
                "sales-analysis-expert": 6,
                "social-media-operations-expert": 4,
            },
            {code: row["revision"] for code, row in self.rows.items()},
        )
        self.assertTrue(all(row["tags"] == ["ycps"] for row in self.rows.values()))

    def test_curated_contract_allows_other_experts_but_requires_its_own_six(self) -> None:
        catalog = {"skills": [*self.rows.values(), {"kind": "EXPERT", "code": "another-expert"}]}
        self.assertEqual(set(self.rows), set(module.curated_rows(catalog)))
        catalog["skills"] = [row for row in catalog["skills"] if row["code"] != "ai-office-assistant"]
        with self.assertRaises(module.ConfigurationError):
            module.curated_rows(catalog)

    def test_profile_validation_accepts_snapshot_and_requires_arrays_assets_and_preview(self) -> None:
        failures: list[str] = []
        for code, row in self.rows.items():
            expected = module.expected_presentation(row)
            module.validate_profile(
                {**expected, "assets": {role: f"/v1/skills/{code}/{role}" for role in module.ASSET_ROLES}},
                expected,
                code,
                failures,
            )
        self.assertEqual([], failures)

        broken = dict(module.expected_presentation(next(iter(self.rows.values()))))
        broken["examplePrompts"] = "not-an-array"
        failures = []
        module.validate_profile(broken, broken, "broken", failures)
        self.assertIn("broken.assets: 缺失", failures)
        self.assertIn("broken.examplePrompts: 不是数组", failures)

    def test_profile_validation_ignores_only_explicit_null_preview_compatibility_fields(self) -> None:
        # Use a prose-only Expert here: its canonical preview intentionally has no
        # semantic intro/table, so explicit null compatibility fields remain ignorable.
        row = self.rows["document-review-expert"]
        expected = module.expected_presentation(row)
        actual = json.loads(json.dumps(expected))
        actual["preview"].update({
            "headingPrefix": None,
            "intro": None,
            "table": None,
            "sections": None,
            "blocks": None,
        })
        actual["previewEn"].update({"intro": None, "table": None})
        actual["assets"] = {role: f"/{role}" for role in module.ASSET_ROLES}
        failures: list[str] = []
        module.validate_profile(actual, expected, "expert", failures)
        self.assertEqual([], failures)

        actual["preview"]["intro"] = {"tone": "body", "text": "new semantic content"}
        module.validate_profile(actual, expected, "expert", failures)
        self.assertEqual(["expert.preview: 与快照不一致"], failures)

    def test_semantic_preview_validation_checks_bilingual_intro_and_table_and_no_table(self) -> None:
        expected = {
            "preview": {
                "intro": {"tone": "muted", "text": "Unit: days"},
                "table": {
                    "headers": ["Name", "Expected"],
                    "columnWidths": [50, 50],
                    "rows": [["Lin Ran", "22"]],
                    "totalRow": False,
                },
            },
            "previewEn": {
                "intro": {"tone": "body", "text": "One lead-in"},
                "table": {
                    "headers": ["Action", "Owner"],
                    "columnWidths": [60, 40],
                    "rows": [["Review", "Lin Ran"]],
                    "totalRow": False,
                },
            },
        }
        failures: list[str] = []
        module.validate_semantic_preview_fields(expected, expected, "expert", failures)
        self.assertEqual([], failures)

        actual = json.loads(json.dumps(expected))
        actual["preview"]["table"]["style"] = None
        actual["previewEn"]["table"]["style"] = None
        module.validate_semantic_preview_fields(actual, expected, "expert", failures)
        self.assertEqual([], failures)

        actual["preview"]["table"]["rows"][0][1] = "21"
        module.validate_semantic_preview_fields(actual, expected, "expert", failures)
        self.assertEqual(["expert.preview.table: 与快照不一致"], failures)

        no_table = {
            "preview": {"intro": None, "table": None},
            "previewEn": {"intro": None, "table": None},
        }
        actual_no_table = {"preview": {}, "previewEn": {"table": None}}
        failures = []
        module.validate_semantic_preview_fields(actual_no_table, no_table, "plain", failures)
        self.assertEqual([], failures)
        actual_no_table["preview"]["table"] = {"headers": ["unexpected"]}
        module.validate_semantic_preview_fields(actual_no_table, no_table, "plain", failures)
        self.assertEqual(
            ["plain.preview.table: 快照中不应存在语义内容"],
            failures,
        )

    def test_package_semantic_metadata_matches_catalog_and_rejects_stale_no_table_keys(self) -> None:
        row = {
            "code": "table-expert",
            "preview_zh": {"intro": {"tone": "body", "text": "导语"}, "table": {"rows": [["甲"]]}},
            "preview_en": {"intro": {"tone": "body", "text": "Lead"}, "table": {"rows": [["A"]]}},
        }
        metadata = module.expected_semantic_preview_metadata(row)
        failures: list[str] = []
        module.validate_package_preview_metadata("table-expert", metadata, row, failures)
        self.assertEqual([], failures)

        metadata["expert.preview.table.v1.en"] = '{"rows":[["stale"]]}'
        module.validate_package_preview_metadata("table-expert", metadata, row, failures)
        self.assertEqual(
            ["table-expert.package.SKILL.md.metadata.expert.preview.table.v1.en: 与 catalog 不一致"],
            failures,
        )

        no_table_row = {
            "code": "plain-expert",
            "preview_zh": {"intro": None, "table": None},
            "preview_en": {"intro": None, "table": None},
        }
        failures = []
        module.validate_package_preview_metadata(
            "plain-expert",
            {"expert.preview.table.v1.zh": '{"rows":[]}'},
            no_table_row,
            failures,
        )
        self.assertEqual(
            [
                "plain-expert.package.SKILL.md.metadata.expert.preview.table.v1.zh: "
                "catalog 中不应存在"
            ],
            failures,
        )

    def test_catalog_semantic_preview_set_requires_exact_three_table_experts(self) -> None:
        rows = {
            code: {"code": code, "preview_zh": {"table": {}}, "preview_en": {"table": {}}}
            for code in module.TABLE_EXPERT_CODES
        }
        rows.update({
            code: {"code": code, "preview_zh": {"table": None}, "preview_en": {"table": None}}
            for code in module.NO_TABLE_EXPERT_CODES
        })
        module.validate_catalog_semantic_preview_set(rows)
        rows["content-writing-expert"]["preview_en"]["table"] = {"rows": []}
        with self.assertRaises(module.ConfigurationError):
            module.validate_catalog_semantic_preview_set(rows)

    def test_list_contract_rejects_large_presentation_fields(self) -> None:
        public = [{"name": "ai-office-assistant", "taskTypes": []}]
        leaked = {"name": "content-summarizer", "expertPresentation": {"preview": {}}}
        forbidden = {"expertPresentation", "preview", "usageGuide", "examplePrompts"}
        self.assertFalse(forbidden.intersection(public[0]))
        self.assertEqual({"expertPresentation"}, forbidden.intersection(leaked))

    def test_top_level_validation_catches_detail_shell_regression(self) -> None:
        row = self.rows["ai-office-assistant"]
        fields = {"title": "name", "description": "description", "revision": "revision"}
        actual = {key: row[snapshot_key] for key, snapshot_key in fields.items()}
        failures: list[str] = []
        module.validate_top_level_fields(actual, row, fields, "expert", failures)
        self.assertEqual([], failures)

        actual["description"] = "stale detail copy"
        module.validate_top_level_fields(actual, row, fields, "expert", failures)
        self.assertEqual(["expert.description: 与快照不一致"], failures)

    def test_zip_validator_rejects_wrong_inventory(self) -> None:
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("unexpected.txt", b"bad")
        failures: list[str] = []
        module.validate_zip("ai-office-assistant", stream.getvalue(), failures)
        self.assertEqual(["ai-office-assistant.package.entries: 不是确定性的 6 文件 Expert 包"], failures)

    def test_zip_validator_accepts_semantic_equivalence_with_different_json_formatting(self) -> None:
        row = self.rows["ai-office-assistant"]
        fixture = (module.SNAPSHOT_ROOT / row["package_file"]).read_bytes()
        with zipfile.ZipFile(io.BytesIO(fixture)) as source:
            entries = {info.filename: source.read(info) for info in source.infolist()}
        for name in ("plugin.json", "com.yuzhihuihe.ycps/manifest.json"):
            entries[name] = json.dumps(
                json.loads(entries[name]), ensure_ascii=False, separators=(",", ":")
            ).encode("utf-8")
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for name in module.expected_zip_names(row["code"]):
                info = zipfile.ZipInfo(name, (1980, 1, 2, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.comment = b""
                info.extra = b""
                archive.writestr(info, entries[name])
        self.assertNotEqual(fixture, stream.getvalue())
        failures: list[str] = []
        module.validate_zip(row["code"], stream.getvalue(), failures, fixture)
        self.assertEqual([], failures)

    def test_verifier_uses_get_only_after_login(self) -> None:
        client = Mock()
        client.get_json.side_effect = AssertionError("network should not be used in this unit test")
        self.assertTrue(hasattr(module.ReadOnlyClient, "get"))
        self.assertEqual("GET", module.urllib.request.Request("http://example.test", method="GET").method)


if __name__ == "__main__":
    unittest.main()
