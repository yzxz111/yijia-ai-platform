"""Offline integrity and regression tests for the cloud catalog reproduction manifest."""
from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import sys
import tempfile
import unittest
import warnings
import zipfile
from pathlib import Path
from unittest import mock


SYSTEM_ROOT = Path(__file__).resolve().parent.parent
SNAPSHOT_ROOT = SYSTEM_ROOT / "fixtures" / "catalog-snapshot"
CATALOG_PATH = SNAPSHOT_ROOT / "catalog.json"
MANIFEST_PATH = SNAPSHOT_ROOT / "repro-manifest.json"
BUILDER_PATH = Path(__file__).with_name("build-cloud-repro-manifest.py")
spec = importlib.util.spec_from_file_location("cloud_repro_manifest_builder", BUILDER_PATH)
assert spec is not None and spec.loader is not None
builder = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = builder
spec.loader.exec_module(builder)


def digest(path: Path, algorithm: str) -> str:
    return hashlib.new(algorithm, path.read_bytes()).hexdigest()


class CloudReproManifestTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.catalog = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
        cls.manifest = json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))
        cls.rows = {row["code"]: row for row in cls.catalog["skills"]}
        cls.packages = {item["code"]: item for item in cls.manifest["packages"]}
        cls.category_rows = {
            (row["kind"], row["code"]): row for row in cls.catalog["categories"]
        }
        cls.categories = {
            (item["kind"], item["code"]): item for item in cls.manifest["categories"]
        }

    def build_with_catalog(self, catalog: dict) -> dict:
        with tempfile.TemporaryDirectory() as temp_dir:
            catalog_path = Path(temp_dir) / "catalog.json"
            catalog_path.write_text(json.dumps(catalog), encoding="utf-8")
            with mock.patch.object(builder, "CATALOG_PATH", catalog_path):
                return builder.build_manifest()

    def test_manifest_is_deterministic_and_covers_all_cloud_packages(self) -> None:
        self.assertEqual(self.manifest, builder.build_manifest())
        self.assertEqual(self.manifest["packageCount"], len(self.catalog["skills"]))
        self.assertEqual(
            self.manifest["expertCount"],
            sum(row["kind"] == "EXPERT" for row in self.catalog["skills"]),
        )
        self.assertEqual(
            self.manifest["skillCount"],
            sum(row["kind"] == "SKILL" for row in self.catalog["skills"]),
        )
        self.assertEqual(len(self.packages), len(self.catalog["skills"]))
        self.assertEqual(set(self.packages), set(self.rows))
        self.assertTrue(all(row["status"] == "PUBLISHED" for row in self.rows.values()))
        self.assertEqual(
            [item["code"] for item in self.manifest["packages"]], sorted(self.rows)
        )

    def test_archive_identity_and_member_digests_match_catalog(self) -> None:
        for code, row in self.rows.items():
            with self.subTest(code=code):
                item = self.packages[code]
                package_path = SNAPSHOT_ROOT / row["package_file"]
                self.assertEqual(item["artifact"]["sha256"], row["sha256"])
                self.assertEqual(item["artifact"]["md5"], row["md5"])
                self.assertEqual(item["artifact"]["bytes"], row["size_bytes"])
                self.assertEqual(digest(package_path, "sha256"), row["sha256"])
                self.assertEqual(digest(package_path, "md5"), row["md5"])
                self.assertEqual(item["revision"], row["revision"])
                self.assertEqual(item["ycpsVersion"], row["ycps_version"])
                with zipfile.ZipFile(package_path) as archive:
                    actual_names = sorted(archive.namelist())
                    manifest_names = [member["path"] for member in item["members"]]
                    self.assertEqual(manifest_names, actual_names)
                    self.assertEqual(len(manifest_names), row["file_count"])
                    self.assertEqual(
                        item["artifact"]["extractedBytes"], row["extracted_bytes"]
                    )
                    self.assertEqual(
                        sum(member["bytes"] for member in item["members"]),
                        row["extracted_bytes"],
                    )
                    for member in item["members"]:
                        data = archive.read(member["path"])
                        self.assertEqual(len(data), member["bytes"], member["path"])
                        self.assertEqual(
                            hashlib.sha256(data).hexdigest(),
                            member["sha256"],
                            member["path"],
                        )

    def test_expert_metadata_and_referenced_media_are_complete(self) -> None:
        experts = [row for row in self.rows.values() if row["kind"] == "EXPERT"]
        self.assertEqual(len(experts), self.manifest["expertCount"])
        for row in experts:
            with self.subTest(code=row["code"]):
                item = self.packages[row["code"]]
                for source_key, manifest_key in (
                    ("task_types", "taskTypes"),
                    ("task_types_en", "taskTypesEn"),
                    ("expert_presentation", "expertPresentation"),
                ):
                    self.assertEqual(item[manifest_key], row[source_key])
                assets = {asset["role"]: asset for asset in item["assets"]}
                expected_roles = {
                    role for role, prefix in (
                        ("icon", "icon"),
                        ("illustration", "illustration"),
                        ("exampleImage", "example_image"),
                    ) if row.get(f"{prefix}_file")
                }
                self.assertEqual(set(assets), expected_roles)
                for role, prefix in (
                    ("icon", "icon"),
                    ("illustration", "illustration"),
                    ("exampleImage", "example_image"),
                ):
                    if role not in assets:
                        continue
                    asset = assets[role]
                    path = SNAPSHOT_ROOT / asset["file"]
                    self.assertEqual(asset["file"], row[f"{prefix}_file"])
                    self.assertEqual(asset["bytes"], row[f"{prefix}_bytes"])
                    self.assertEqual(asset["md5"], row[f"{prefix}_md5"])
                    self.assertEqual(asset["bytes"], path.stat().st_size)
                    self.assertEqual(asset["md5"], digest(path, "md5"))
                    self.assertEqual(asset["sha256"], digest(path, "sha256"))
                    for dimension in ("width", "height"):
                        expected = row.get(f"{prefix}_{dimension}")
                        if expected is not None:
                            self.assertEqual(asset[dimension], expected)

    def test_ordinary_skills_have_no_expert_presentation_or_media(self) -> None:
        skills = [row for row in self.rows.values() if row["kind"] == "SKILL"]
        self.assertEqual(len(skills), self.manifest["skillCount"])
        for row in skills:
            with self.subTest(code=row["code"]):
                item = self.packages[row["code"]]
                self.assertNotIn("expertPresentation", item)
                self.assertNotIn("assets", item)
                self.assertEqual(item["packageKind"], "SKILL")

    def test_categories_and_cover_bytes_are_complete(self) -> None:
        self.assertEqual(self.manifest["categoryCount"], len(self.catalog["categories"]))
        self.assertEqual(len(self.categories), len(self.catalog["categories"]))
        self.assertEqual(set(self.categories), set(self.category_rows))
        self.assertEqual(
            self.manifest["coverCount"],
            sum(bool(row.get("image_file")) for row in self.catalog["categories"]),
        )
        self.assertEqual(
            [(item["kind"], item["code"]) for item in self.manifest["categories"]],
            sorted(self.category_rows),
        )
        for identity, row in self.category_rows.items():
            with self.subTest(identity=identity):
                item = self.categories[identity]
                self.assertEqual(item["name"], row.get("name"))
                self.assertEqual(item["nameEn"], row.get("name_en"))
                self.assertEqual(item["sortOrder"], row.get("sort_order"))
                self.assertEqual(item["enabled"], row.get("enabled"))
                cover = item["cover"]
                if not row.get("image_file"):
                    self.assertIsNone(cover)
                    continue
                self.assertIsNotNone(cover)
                path = SNAPSHOT_ROOT / row["image_file"]
                self.assertEqual(cover["file"], row["image_file"])
                self.assertEqual(cover["contentType"], row["image_content_type"])
                self.assertEqual(cover["bytes"], row["image_bytes"])
                self.assertEqual(cover["md5"], row["image_md5"])
                self.assertEqual(cover["bytes"], path.stat().st_size)
                self.assertEqual(cover["md5"], digest(path, "md5"))
                self.assertEqual(cover["sha256"], digest(path, "sha256"))
                self.assertEqual(
                    (cover["width"], cover["height"]),
                    builder.image_dimensions(path, cover["contentType"]),
                )
                for key in ("sha256", "width", "height"):
                    expected = row.get(f"image_{key}")
                    if expected is not None:
                        self.assertEqual(cover[key], expected)

    def test_bad_catalog_rows_are_rejected(self) -> None:
        first = self.catalog["skills"][0]
        first_cover_index = next(
            index for index, row in enumerate(self.catalog["categories"])
            if row.get("image_file")
        )
        no_cover_index = next(
            index for index, row in enumerate(self.catalog["categories"])
            if not row.get("image_file")
        )

        def duplicate_code(catalog: dict) -> None:
            catalog["skills"].append(copy.deepcopy(first))

        def missing_package(catalog: dict) -> None:
            catalog["skills"][0]["package_file"] = None

        def unpublished(catalog: dict) -> None:
            catalog["skills"][0]["status"] = "DRAFT"

        def wrong_version(catalog: dict) -> None:
            catalog["skills"][0]["version"] = "99.0.0"

        def wrong_hash(catalog: dict) -> None:
            catalog["skills"][0]["sha256"] = "0" * 64

        def wrong_member_count(catalog: dict) -> None:
            catalog["skills"][0]["file_count"] += 1

        def wrong_extracted_bytes(catalog: dict) -> None:
            catalog["skills"][0]["extracted_bytes"] += 1

        def duplicate_category(catalog: dict) -> None:
            catalog["categories"].append(copy.deepcopy(catalog["categories"][0]))

        def wrong_cover_hash(catalog: dict) -> None:
            catalog["categories"][first_cover_index]["image_md5"] = "0" * 32

        def missing_cover(catalog: dict) -> None:
            catalog["categories"][first_cover_index]["image_file"] = "images/categories/missing.webp"

        def orphan_cover_metadata(catalog: dict) -> None:
            catalog["categories"][no_cover_index]["image_md5"] = "0" * 32

        cases = (
            (duplicate_code, "duplicate or invalid package code"),
            (missing_package, "has no package file"),
            (unpublished, "not a published"),
            (wrong_version, "differs from published version"),
            (wrong_hash, "package digest differs from catalog"),
            (wrong_member_count, "ZIP member count differs from catalog"),
            (wrong_extracted_bytes, "ZIP extracted bytes differ from catalog"),
            (duplicate_category, "duplicate category identity"),
            (wrong_cover_hash, "cover md5 differs from catalog"),
            (missing_cover, "missing .* cover fixture"),
            (orphan_cover_metadata, "has cover metadata but no image file"),
        )
        for mutate, expected_error in cases:
            with self.subTest(mutate=mutate.__name__):
                catalog = copy.deepcopy(self.catalog)
                mutate(catalog)
                with self.assertRaisesRegex(ValueError, expected_error):
                    self.build_with_catalog(catalog)

    def test_duplicate_archive_member_names_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            package_path = root / "duplicate.ycps.zip"
            with warnings.catch_warnings():
                warnings.simplefilter("ignore", UserWarning)
                with zipfile.ZipFile(package_path, "w") as archive:
                    archive.writestr("SKILL.md", "one")
                    archive.writestr("SKILL.md", "two")
            row = {
                "code": "duplicate-member",
                "kind": "SKILL",
                "package_file": package_path.name,
                "size_bytes": package_path.stat().st_size,
                "md5": digest(package_path, "md5"),
                "sha256": digest(package_path, "sha256"),
                "file_count": 2,
                "extracted_bytes": 6,
            }
            with mock.patch.object(builder, "SNAPSHOT_ROOT", root):
                with self.assertRaisesRegex(ValueError, "duplicate archive member"):
                    builder.package_record(row)


if __name__ == "__main__":
    unittest.main()
