"""Offline integrity tests for the full catalog and six curated Expert packages."""
from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import re
import sys
import unittest
import zipfile
from pathlib import Path, PurePosixPath
from xml.etree import ElementTree

from PIL import Image


SYSTEM_ROOT = Path(__file__).resolve().parent.parent
SNAPSHOT_ROOT = SYSTEM_ROOT / "fixtures" / "catalog-snapshot"
CATALOG = SNAPSHOT_ROOT / "catalog.json"
BUILDER = Path(__file__).with_name("build-curated-expert-fixtures.py")
spec = importlib.util.spec_from_file_location("curated_fixture_builder", BUILDER)
assert spec is not None and spec.loader is not None
builder = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = builder
spec.loader.exec_module(builder)

EXPERT_CODES = tuple(builder.CURATED_REVISIONS)
EXPERT_ASSET_ROLES = {
    "icon": "icon",
    "illustration": "illustration",
    "example_image": "exampleImage",
}
EXPERT_EXAMPLES = {
    "ai-office-assistant": (1072, 280),
    "content-summarizer": (1072, 280),
    "content-writing-expert": (1072, 280),
    "document-review-expert": (1072, 280),
    "sales-analysis-expert": (1072, 280),
    "social-media-operations-expert": (1072, 280),
}
CURATED_CATEGORY_IMAGES = {
    "images/categories/content-creation-cover.webp",
    "images/categories/office-productivity-cover.webp",
    "images/categories/sales-business-cover.webp",
}
TABLE_EXPERT_CODES = {
    "ai-office-assistant",
    "content-summarizer",
    "sales-analysis-expert",
}
NO_TABLE_EXPERT_CODES = {
    "content-writing-expert",
    "document-review-expert",
    "social-media-operations-expert",
}
EXPECTED_SEMANTIC_PREVIEWS = {
    "ai-office-assistant": {
        "intro": {
            "zh": {"tone": "muted", "text": "单位：天"},
            "en": {"tone": "muted", "text": "Unit: days"},
        },
        "table": {
            "zh": {
                "headers": ["姓名", "应出勤", "实际出勤", "差额"],
                "columnWidths": [25, 25, 25, 25],
                "rows": [
                    ["林然", "22", "21", "1"],
                    ["周宁", "22", "22", "0"],
                    ["合计", "44", "43", "1"],
                ],
                "totalRow": True,
            },
            "en": {
                "headers": ["Name", "Expected", "Actual", "Difference"],
                "columnWidths": [25, 25, 25, 25],
                "rows": [
                    ["Lin Ran", "22", "21", "1"],
                    ["Zhou Ning", "22", "22", "0"],
                    ["Total", "44", "43", "1"],
                ],
                "totalRow": True,
            },
        },
    },
    "content-summarizer": {
        "intro": {
            "zh": {"tone": "body", "text": "已确认决策：首版定于 9 月 18 日上线。"},
            "en": {"tone": "body", "text": "Confirmed: The first release launches on September 18."},
        },
        "table": {
            "zh": {
                "headers": ["待办事项", "负责人", "截止日期"],
                "columnWidths": [52, 19, 29],
                "rows": [
                    ["补齐注册流程", "李明", "9 月 15 日"],
                    ["完成回归测试", "王悦", "9 月 16 日"],
                    ["跟进短信服务报价", "陈晨", "9 月 14 日"],
                ],
                "totalRow": False,
            },
            "en": {
                "headers": ["Action item", "Owner", "Due date"],
                "columnWidths": [52, 19, 29],
                "rows": [
                    ["Complete sign-up flow", "Li Ming", "Sep 15"],
                    ["Finish regression testing", "Wang Yue", "Sep 16"],
                    ["Follow up on SMS pricing", "Chen Chen", "Sep 14"],
                ],
                "totalRow": False,
            },
        },
    },
    "sales-analysis-expert": {
        "intro": {
            "zh": {"tone": "body", "text": "净销售额 54 万元，环比增长 8%"},
            "en": {"tone": "body", "text": "Net sales: CNY 540,000, up 8% month over month"},
        },
        "table": {
            "zh": {
                "headers": ["产品", "7 月（万元）", "8 月（万元）", "环比"],
                "columnWidths": [18, 30, 30, 22],
                "rows": [
                    ["A", "30", "36", "+20%"],
                    ["B", "20", "18", "−10%"],
                    ["合计", "50", "54", "+8%"],
                ],
                "totalRow": True,
            },
            "en": {
                "headers": ["Product", "Jul (CNY 10k)", "Aug (CNY 10k)", "MoM"],
                "columnWidths": [18, 30, 30, 22],
                "rows": [
                    ["A", "30", "36", "+20%"],
                    ["B", "20", "18", "−10%"],
                    ["Total", "50", "54", "+8%"],
                ],
                "totalRow": True,
            },
        },
    },
}


def digest_pair(plugin: dict, runtime: dict) -> str:
    value = json.dumps(
        {"plugin": plugin, "runtime": runtime},
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode()
    return hashlib.sha256(value).hexdigest()


def dependency_digest(runtime: dict) -> str:
    value = json.dumps(
        sorted(runtime.get("dependencies", []), key=lambda item: item.get("key", "")),
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode()
    return hashlib.sha256(value).hexdigest()


def without_null_compatibility_fields(value):
    """Ignore optional API compatibility fields that are explicitly serialized as null."""
    if isinstance(value, dict):
        return {
            key: without_null_compatibility_fields(item)
            for key, item in value.items()
            if item is not None
        }
    if isinstance(value, list):
        return [without_null_compatibility_fields(item) for item in value]
    return value


def snapshot_path(relative: str, directory: str) -> Path:
    """Resolve a catalog file reference without permitting an escape from its fixture directory."""
    if not isinstance(relative, str) or "\\" in relative:
        raise ValueError(f"invalid {directory} fixture path: {relative!r}")
    path = PurePosixPath(relative)
    if path.is_absolute() or ".." in path.parts or path.as_posix() != relative:
        raise ValueError(f"invalid {directory} fixture path: {relative!r}")
    if not path.parts or path.parts[0] != directory:
        raise ValueError(f"fixture path is outside {directory}: {relative!r}")
    return SNAPSHOT_ROOT.joinpath(*path.parts)


def catalog_image_references(catalog: dict) -> list[tuple[dict, str, str]]:
    """Find every image file recorded by a category or skill row."""
    references = []
    for row in [*catalog["categories"], *catalog["skills"]]:
        for key, value in row.items():
            if key.endswith("_file") and isinstance(value, str) and value.startswith("images/"):
                references.append((row, key.removesuffix("_file"), value))
    return references


class CatalogSnapshotIntegrityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        cls.experts = {
            row["code"]: row for row in cls.catalog["skills"] if row.get("kind") == "EXPERT"
        }

    def test_image_inventory_matches_all_catalog_references(self) -> None:
        self.assertTrue(set(EXPERT_CODES) <= set(self.experts))
        covers = [row for row in self.catalog["categories"] if row.get("image_file")]
        for code in EXPERT_CODES:
            with self.subTest(code=code):
                for role in EXPERT_ASSET_ROLES:
                    self.assertIsInstance(self.experts[code].get(f"{role}_file"), str)

        references = [relative for _, _, relative in catalog_image_references(self.catalog)]
        expected = set(references)
        self.assertEqual(len(references), len(expected), "catalog image paths must be unique")
        self.assertTrue(CURATED_CATEGORY_IMAGES <= expected)
        for relative in expected:
            snapshot_path(relative, "images")
        actual = {
            path.relative_to(SNAPSHOT_ROOT).as_posix()
            for path in (SNAPSHOT_ROOT / "images").rglob("*")
            if path.is_file()
        }
        self.assertSetEqual(expected, actual)

    def test_every_referenced_image_has_matching_digests_and_dimensions(self) -> None:
        for row, role, relative in catalog_image_references(self.catalog):
            with self.subTest(code=row["code"], role=role):
                path = snapshot_path(relative, "images")
                content = path.read_bytes()
                self.assertEqual(len(content), row[f"{role}_bytes"])
                self.assertEqual(hashlib.md5(content).hexdigest(), row[f"{role}_md5"])
                self.assertEqual(hashlib.sha256(content).hexdigest(), row[f"{role}_sha256"])
                mime = row[f"{role}_content_type"].split(";", 1)[0].lower()
                suffix = {
                    "image/png": ".png",
                    "image/webp": ".webp",
                    "image/jpeg": ".jpg",
                    "image/gif": ".gif",
                    "image/svg+xml": ".svg",
                }[mime]
                self.assertEqual(suffix, path.suffix.lower())
                if mime == "image/svg+xml":
                    self.assertEqual("svg", ElementTree.fromstring(content).tag.rsplit("}", 1)[-1])
                    self.assertNotIn(f"{role}_width", row)
                    self.assertNotIn(f"{role}_height", row)
                else:
                    with Image.open(path) as image:
                        self.assertEqual(
                            (image.width, image.height),
                            (row[f"{role}_width"], row[f"{role}_height"]),
                        )
                        self.assertEqual(mime, Image.MIME[image.format])
                        image.load()

    def test_catalog_contains_exact_bilingual_v73_presentation_for_all_six(self) -> None:
        expected = builder.migration_profiles()
        self.assertTrue(set(EXPERT_CODES) <= set(self.experts))
        for code in EXPERT_CODES:
            with self.subTest(code=code):
                row = self.experts[code]
                # Operational gateway.skill.tags are intentionally unchanged by the Expert
                # presentation migration; taskTypes are the separate business taxonomy.
                self.assertEqual(["ycps"], row["tags"])
                for key, value in expected[code].items():
                    self.assertEqual(
                        without_null_compatibility_fields(value),
                        without_null_compatibility_fields(row.get(key)),
                        key,
                    )
                presentation = row.get("expert_presentation") or {}
                self.assertEqual(expected[code]["task_types"], presentation["taskTypes"])
                self.assertEqual(expected[code]["task_types_en"], presentation["taskTypesEn"])
                self.assertEqual(expected[code]["usage_guide"], presentation["usageGuide"])
                self.assertEqual(expected[code]["usage_guide_en"], presentation["usageGuideEn"])
                self.assertEqual(expected[code]["example_prompts"], presentation["examplePrompts"])
                self.assertEqual(expected[code]["example_prompts_en"], presentation["examplePromptsEn"])
                self.assertEqual(
                    without_null_compatibility_fields(expected[code]["preview_zh"]),
                    without_null_compatibility_fields(presentation["preview"]),
                )
                self.assertEqual(
                    without_null_compatibility_fields(expected[code]["preview_en"]),
                    without_null_compatibility_fields(presentation["previewEn"]),
                )
                if code == "ai-office-assistant":
                    canvas = presentation.get("canvas", {}).get("zh")
                    self.assertEqual((1080, 704), (canvas["width"], canvas["height"]))
                    self.assertEqual(25, len(canvas["nodes"]))
                else:
                    self.assertNotIn("canvas", presentation)

    def test_catalog_contains_exact_preview_intros_and_semantic_tables(self) -> None:
        self.assertSetEqual(TABLE_EXPERT_CODES, set(EXPECTED_SEMANTIC_PREVIEWS))
        self.assertSetEqual(TABLE_EXPERT_CODES, set(EXPERT_CODES) - NO_TABLE_EXPERT_CODES)
        for code, expected in EXPECTED_SEMANTIC_PREVIEWS.items():
            with self.subTest(code=code):
                row = self.experts[code]
                self.assertEqual(expected["intro"]["zh"], row["preview_zh"].get("intro"))
                self.assertEqual(expected["intro"]["en"], row["preview_en"].get("intro"))
                self.assertEqual(expected["table"]["zh"], row["preview_zh"].get("table"))
                self.assertEqual(expected["table"]["en"], row["preview_en"].get("table"))

        for code in NO_TABLE_EXPERT_CODES:
            with self.subTest(code=code):
                self.assertIsNone(self.experts[code]["preview_zh"].get("table"))
                self.assertIsNone(self.experts[code]["preview_en"].get("table"))

    def test_curated_images_keep_portable_dimensions_and_formats(self) -> None:
        for code in EXPERT_CODES:
            with self.subTest(code=code):
                row = self.experts[code]
                illustration = SNAPSHOT_ROOT / row["illustration_file"]
                example = SNAPSHOT_ROOT / row["example_image_file"]
                with Image.open(illustration) as image:
                    self.assertEqual("WEBP", image.format)
                    self.assertEqual((1440, 480), image.size)
                self.assertLessEqual(illustration.stat().st_size, 640 * 1024)
                with Image.open(example) as image:
                    self.assertEqual(EXPERT_EXAMPLES[code], image.size)
                    self.assertEqual("PNG", image.format)

    def test_all_packages_match_catalog_and_embedded_references(self) -> None:
        rows = [row for row in self.catalog["skills"] if row.get("package_file")]
        self.assertTrue(set(EXPERT_CODES) <= {row["code"] for row in rows})
        expected_packages = {row["package_file"] for row in rows}
        self.assertEqual(len(rows), len(expected_packages), "catalog package paths must be unique")
        actual_packages = {
            path.relative_to(SNAPSHOT_ROOT).as_posix()
            for path in (SNAPSHOT_ROOT / "packages").rglob("*")
            if path.is_file()
        }
        self.assertSetEqual(expected_packages, actual_packages)

        for row in rows:
            code = row["code"]
            with self.subTest(code=code):
                package = snapshot_path(row["package_file"], "packages")
                content = package.read_bytes()
                self.assertEqual(len(content), row["size_bytes"])
                self.assertEqual(hashlib.md5(content).hexdigest(), row["md5"])
                self.assertEqual(hashlib.sha256(content).hexdigest(), row["sha256"])
                with zipfile.ZipFile(package) as archive:
                    self.assertIsNone(archive.testzip(), "ZIP entry failed CRC validation")
                    infos = archive.infolist()
                    names = [info.filename for info in infos]
                    self.assertEqual(len(names), len(set(names)), "duplicate ZIP entry")
                    self.assertEqual(len(names), row["file_count"])
                    self.assertEqual(sum(info.file_size for info in infos), row["extracted_bytes"])
                    self.assertTrue(all(not info.is_dir() for info in infos))
                    for name in names:
                        path = PurePosixPath(name)
                        self.assertFalse(path.is_absolute() or ".." in path.parts or "\\" in name)

                    plugin_path = "plugin.json"
                    runtime_path = "com.yuzhihuihe.ycps/manifest.json"
                    skill_path = f"skills/{code}/SKILL.md"
                    self.assertTrue({plugin_path, runtime_path, skill_path} <= set(names))
                    plugin = json.loads(archive.read(plugin_path))
                    runtime = json.loads(archive.read(runtime_path))
                    self.assertEqual(row["plugin_manifest"], plugin)
                    self.assertEqual(row["runtime_manifest"], runtime)
                    self.assertEqual(digest_pair(plugin, runtime), row["manifest_sha256"])
                    self.assertEqual(dependency_digest(runtime), row["dependency_lock_sha256"])
                    extension = plugin["extensions"]["com.yuzhihuihe.ycps"]
                    self.assertEqual(code, extension["entrySkill"])
                    self.assertEqual(row["kind"], extension["kind"])
                    self.assertEqual(row["package_kind"], extension["kind"])
                    self.assertEqual(row["ycps_version"], extension["ycpsVersion"])
                    self.assertEqual(row["version"], plugin["version"])
                    self.assertEqual("YCPS", row["package_format"])

                    skill = archive.read(skill_path).decode("utf-8")
                    for relative in set(re.findall(r"\b(?:assets|references)/[A-Za-z0-9_./-]+", skill)):
                        relative = relative.rstrip(".")
                        path = PurePosixPath(relative)
                        self.assertFalse(path.is_absolute() or ".." in path.parts)
                        self.assertIn(f"skills/{code}/{path.as_posix()}", names)

                    if row["kind"] == "EXPERT":
                        metadata = builder.package_skill_metadata(archive.read(skill_path))
                        for metadata_role in EXPERT_ASSET_ROLES.values():
                            metadata_key = f"expert.asset.{metadata_role}.path"
                            asset_path = metadata.get(metadata_key)
                            if asset_path is None:
                                continue
                            self.assertTrue(asset_path.startswith("assets/"))
                            self.assertNotIn("..", PurePosixPath(asset_path).parts)
                            embedded = f"skills/{code}/{asset_path}"
                            self.assertIn(embedded, names)

    def test_curated_packages_are_ycps_11_with_three_assets(self) -> None:
        for code, target_revision in builder.CURATED_REVISIONS.items():
            with self.subTest(code=code):
                row = self.experts[code]
                package = SNAPSHOT_ROOT / row["package_file"]
                content = package.read_bytes()
                self.assertEqual(target_revision, row["revision"])
                self.assertEqual(hashlib.sha256(content).hexdigest(), row["sha256"])
                self.assertEqual(hashlib.md5(content).hexdigest(), row["md5"])
                self.assertEqual(len(content), row["size_bytes"])
                with zipfile.ZipFile(package) as archive:
                    infos = archive.infolist()
                    expected_names = [
                        "com.yuzhihuihe.ycps/manifest.json",
                        "plugin.json",
                        f"skills/{code}/SKILL.md",
                        f"skills/{code}/assets/example-image.png",
                        f"skills/{code}/assets/icon.png",
                        f"skills/{code}/assets/illustration.webp",
                    ]
                    self.assertEqual(expected_names, [info.filename for info in infos])
                    self.assertEqual(expected_names, sorted(expected_names, key=lambda value: value.encode()))
                    self.assertTrue(all(info.date_time == (1980, 1, 2, 0, 0, 0) for info in infos))
                    self.assertTrue(all(info.comment == b"" and info.extra == b"" for info in infos))
                    self.assertTrue(all(info.compress_type == zipfile.ZIP_DEFLATED for info in infos))
                    self.assertEqual(6, row["file_count"])
                    self.assertEqual(sum(info.file_size for info in infos), row["extracted_bytes"])
                    plugin = json.loads(archive.read("plugin.json"))
                    runtime = json.loads(archive.read("com.yuzhihuihe.ycps/manifest.json"))
                    ext = plugin["extensions"]["com.yuzhihuihe.ycps"]
                    self.assertEqual("YCPS", row["package_format"])
                    self.assertEqual("1.1.0", row["ycps_version"])
                    self.assertEqual("EXPERT", row["package_kind"])
                    self.assertTrue(plugin["$schema"].endswith("plugin.schema.json"))
                    self.assertEqual("1.1.0", ext["ycpsVersion"])
                    self.assertEqual(["expert-presentation-v1"], ext["requiredFeatures"])
                    self.assertEqual("EXPERT", ext["kind"])
                    self.assertEqual(code, ext["entrySkill"])
                    self.assertEqual(builder.RUNTIME_SCHEMA, runtime["$schema"])
                    self.assertEqual(builder.MIN_CLIENT_VERSION, runtime["hostRequirements"]["minClientVersion"])
                    self.assertEqual(builder.MIN_CLIENT_VERSION, row["version_min_client_version"])
                    self.assertEqual(digest_pair(plugin, runtime), row["manifest_sha256"])
                    self.assertEqual(dependency_digest(runtime), row["dependency_lock_sha256"])
                    self.assertEqual(
                        (SNAPSHOT_ROOT / row["icon_file"]).read_bytes(),
                        archive.read(f"skills/{code}/assets/icon.png"),
                    )
                    self.assertEqual(
                        (SNAPSHOT_ROOT / row["illustration_file"]).read_bytes(),
                        archive.read(f"skills/{code}/assets/illustration.webp"),
                    )
                    self.assertEqual(
                        (SNAPSHOT_ROOT / row["example_image_file"]).read_bytes(),
                        archive.read(f"skills/{code}/assets/example-image.png"),
                    )
                    skill = archive.read(f"skills/{code}/SKILL.md").decode("utf-8")
                    self.assertFalse(skill.startswith("---\n---\n"))
                    for key in (
                        "title.zh", "title.en", "summary.zh", "summary.en", "description.zh",
                        "description.en", "expert.usageGuide.zh", "expert.usageGuide.en",
                        "expert.preview.heading.zh", "expert.preview.heading.en",
                        "expert.asset.icon.path", "expert.asset.illustration.path",
                        "expert.asset.exampleImage.path",
                    ):
                        self.assertIn(f"{key}:", skill)
                    if code == "ai-office-assistant":
                        self.assertIn("expert.canvas.v1.zh:", skill)

    def test_package_preview_metadata_matches_snapshot_and_has_no_stale_tables(self) -> None:
        for code in EXPERT_CODES:
            with self.subTest(code=code):
                row = self.experts[code]
                package = SNAPSHOT_ROOT / row["package_file"]
                skill_path = f"skills/{code}/SKILL.md"
                with zipfile.ZipFile(package) as archive:
                    metadata = builder.package_skill_metadata(archive.read(skill_path))

                for language, preview_key in (("zh", "preview_zh"), ("en", "preview_en")):
                    preview = row[preview_key]
                    intro_key = f"expert.preview.intro.v1.{language}"
                    table_key = f"expert.preview.table.v1.{language}"
                    intro = preview.get("intro")
                    table = preview.get("table")
                    if intro is None:
                        self.assertNotIn(intro_key, metadata)
                    else:
                        self.assertEqual(
                            json.dumps(
                                intro, ensure_ascii=False, separators=(",", ":"), sort_keys=True
                            ),
                            metadata.get(intro_key),
                        )
                    if table is None:
                        self.assertNotIn(table_key, metadata)
                    else:
                        self.assertEqual(
                            json.dumps(
                                table, ensure_ascii=False, separators=(",", ":"), sort_keys=True
                            ),
                            metadata.get(table_key),
                        )

    def test_deterministic_zip_builder_is_byte_identical(self) -> None:
        entries = {
            "z.txt": b"z",
            "a.txt": "固定 UTF-8".encode("utf-8"),
            "nested/file.bin": bytes(range(32)),
        }
        self.assertEqual(
            builder.deterministic_zip_bytes(copy.deepcopy(entries)),
            builder.deterministic_zip_bytes(copy.deepcopy(entries)),
        )


if __name__ == "__main__":
    unittest.main()
