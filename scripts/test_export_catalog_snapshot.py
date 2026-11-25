"""Regression tests for selecting one immutable package per exported catalog skill."""
from __future__ import annotations

import importlib.util
import hashlib
import json
import sys
import tempfile
import unittest
from unittest.mock import patch
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("export-catalog-snapshot.py")
SPEC = importlib.util.spec_from_file_location("export_catalog_snapshot", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
exporter = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = exporter
SPEC.loader.exec_module(exporter)


class ExportCatalogSnapshotTest(unittest.TestCase):
    def test_selects_published_revision_without_duplicate_package_overwrite(self) -> None:
        common = {
            "code": "curated-expert",
            "kind": "EXPERT",
            "published_version": "1.0.0",
            "version": "1.0.0",
        }
        rows = [
            {**common, "revision": 2, "version_status": "ARCHIVED"},
            {**common, "revision": 4, "version_status": "PUBLISHED"},
            {**common, "revision": 5, "version_status": "READY"},
            {
                "code": "plain-skill",
                "kind": "SKILL",
                "published_version": None,
                "version": None,
                "revision": None,
                "version_status": None,
            },
        ]

        selected = exporter.select_snapshot_versions(rows)

        self.assertEqual(["curated-expert", "plain-skill"], [row["code"] for row in selected])
        expert = next(row for row in selected if row["code"] == "curated-expert")
        self.assertEqual(4, expert["revision"])
        self.assertEqual("PUBLISHED", expert["version_status"])

    def test_exports_png_illustration_and_preserves_package_bytes(self) -> None:
        from io import BytesIO
        from PIL import Image
        image = BytesIO()
        Image.new("RGB", (1440, 480), "#315a70").save(image, format="PNG")
        png = image.getvalue()
        package = b"fixture package bytes"
        row = {
            "id": "expert-id", "code": "new-expert", "kind": "EXPERT",
            "version": "1.0.0", "published_version": "1.0.0", "version_id": "version-id",
            "revision": 1, "version_status": "PUBLISHED", "illustration_object_key": "opaque-key",
            "file_name": "new-expert-upload-r8-patched.ycps.zip", "size_bytes": len(package),
            "sha256": hashlib.sha256(package).hexdigest(), "md5": hashlib.md5(package).hexdigest(),
        }
        class Cloud:
            def get(self, path):
                if path.endswith("/illustration"):
                    return png, "image/png"
                return package, "application/zip"
        with tempfile.TemporaryDirectory() as directory, patch.object(exporter, "psql", return_value=[row]):
            root = Path(directory)
            result = exporter.export_skills(Cloud(), root)[0]
            self.assertEqual("PUBLISHED", result["version_status"])
            self.assertEqual("packages/new-expert-1.0.0.ycps.zip", result["package_file"])
            self.assertEqual("new-expert-upload-r8-patched.ycps.zip", result["file_name"])
            self.assertEqual(1440, result["illustration_width"])
            self.assertEqual(480, result["illustration_height"])
            self.assertEqual(hashlib.sha256(png).hexdigest(), result["illustration_sha256"])
            self.assertEqual(package, (root / result["package_file"]).read_bytes())

    def test_category_kind_collision_keeps_both_cover_files(self) -> None:
        from io import BytesIO
        from PIL import Image
        stream = BytesIO()
        Image.new("RGB", (20, 10), "#507040").save(stream, format="PNG")
        png = stream.getvalue()
        rows = [{"kind": kind, "code": "shared", "image_object_key": "key"} for kind in ("SKILL", "EXPERT")]
        class Cloud:
            def get(self, path): return png, "image/png"
        with tempfile.TemporaryDirectory() as directory, patch.object(exporter, "psql", return_value=rows):
            result = exporter.export_categories(Cloud(), Path(directory))
            self.assertEqual(2, len({row["image_file"] for row in result}))
            for row in result:
                self.assertEqual(hashlib.sha256(png).hexdigest(), row["image_sha256"])
                self.assertEqual((20, 10), (row["image_width"], row["image_height"]))

    def test_same_filename_package_update_replaces_bytes_and_removes_only_stale_cover(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory); old = base / "snapshot"; stage = base / "stage"
            for root in (old, stage):
                (root / "packages").mkdir(parents=True)
                (root / "images/categories").mkdir(parents=True)
            previous = {"categories": [{"image_file": "images/categories/cover.webp"}], "skills": [{"package_file": "packages/expert.ycps.zip"}]}
            current = {"categories": [{"image_file": "images/categories/cover.png"}], "skills": [{"package_file": "packages/expert.ycps.zip"}]}
            (old / "catalog.json").write_text(json.dumps(previous))
            (stage / "catalog.json").write_text(json.dumps(current))
            (old / "packages/expert.ycps.zip").write_bytes(b"old")
            (stage / "packages/expert.ycps.zip").write_bytes(b"new")
            (old / "images/categories/cover.webp").write_bytes(b"old cover")
            (stage / "images/categories/cover.png").write_bytes(b"new cover")
            (old / "README.md").write_text("retain documentation")
            exporter.promote_snapshot(stage, old)
            self.assertEqual(b"new", (old / "packages/expert.ycps.zip").read_bytes())
            self.assertFalse((old / "images/categories/cover.webp").exists())
            self.assertEqual("retain documentation", (old / "README.md").read_text())

    def test_failed_export_does_not_modify_existing_snapshot(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / "snapshot"; root.mkdir()
            (root / "catalog.json").write_text("original")
            def fail(cloud, stage, base):
                (stage / "partial.bin").write_bytes(b"incomplete download")
                raise RuntimeError("download failed")
            with patch.object(sys, "argv", ["export", "--output", str(root)]), \
                    patch.object(exporter, "load_admin", return_value={}), \
                    patch.object(exporter.Cloud, "login"), patch.object(exporter, "export_to", side_effect=fail):
                with self.assertRaisesRegex(RuntimeError, "download failed"):
                    exporter.main()
            self.assertEqual("original", (root / "catalog.json").read_text())
            self.assertEqual(["catalog.json"], [path.name for path in root.iterdir()])

    def test_rejects_unsafe_package_names(self) -> None:
        for name in ("../escape.zip", "a/b.zip", "a\\b.zip", "D:escape.zip", "..", ""):
            with self.subTest(name=name), self.assertRaises(ValueError):
                exporter.safe_filename(name)


if __name__ == "__main__":
    unittest.main()
