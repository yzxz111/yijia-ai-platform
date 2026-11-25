"""针对 catalog snapshot revision 恢复的离线单元测试。"""
from __future__ import annotations

import importlib.util
import io
import json
import sys
import unittest
from contextlib import redirect_stdout
from pathlib import Path


SCRIPT = Path(__file__).with_name("restore-catalog-snapshot.py")
SPEC = importlib.util.spec_from_file_location("restore_catalog_snapshot", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
restore = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = restore
SPEC.loader.exec_module(restore)

CATALOG = Path(__file__).resolve().parent.parent / "fixtures" / "catalog-snapshot" / "catalog.json"


def snapshot_row(revision: int) -> dict:
    return {
        "code": "article-writer",
        "name": "文章写作",
        "name_en": "Article Writer",
        "category": "内容与营销",
        "tags": ["ycps"],
        "summary": "summary",
        "summary_en": "summary",
        "description": "description",
        "description_en": "description",
        "icon": None,
        "kind": "SKILL",
        "min_client_version": "2026.6.0",
        "sort_weight": 0,
        "version": "1.0.0",
        "revision": revision,
        "version_min_client_version": "2026.6.0",
        "package_file": "packages/article-writer-1.0.0.ycps.zip",
    }


def expert_snapshot_row(revision: int | None = None) -> dict:
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    row = next(item for item in catalog["skills"] if item["code"] == "ai-office-assistant")
    # Default to the catalog's current target; explicit values below model revision-gap cases.
    if revision is not None:
        row["revision"] = revision
    return row


def imported_expert_snapshot_row(tags: list[str] | None = None) -> dict:
    row = expert_snapshot_row(1)
    row["code"] = "new-imported-expert"
    row["tags"] = [] if tags is None else tags
    row["icon_file"] = "images/skills/new-imported-expert-icon.png"
    row["illustration_file"] = "images/skills/new-imported-expert-illustration.png"
    row["example_image_file"] = "images/skills/new-imported-expert-example.png"
    row["package_file"] = "packages/new-imported-expert-1.0.0.ycps.zip"
    return row


def served_expert_media(row: dict) -> dict[str, tuple[bytes, str]]:
    code = row["code"]
    icon = restore.SNAPSHOT / row["icon_file"]
    illustration = restore.SNAPSHOT / row["illustration_file"]
    example = restore.SNAPSHOT / row["example_image_file"]
    return {
        f"/v1/skills/{code}/icon": (icon.read_bytes(), restore.media_type_of(icon)),
        f"/v1/skills/{code}/illustration": (
            illustration.read_bytes(), restore.media_type_of(illustration)),
        f"/v1/skills/{code}/example-image": (
            example.read_bytes(), restore.media_type_of(example)),
    }


class FakeClient:
    def __init__(
            self,
            existing_revision: int | None,
            media: dict[str, str | None] | None = None,
            media_status: int = 200) -> None:
        self.existing = existing_revision is not None
        self.revision = existing_revision
        self.version_status = "PUBLISHED" if existing_revision is not None else None
        self.media = dict(media or {})
        self.media_status = media_status
        self.calls: list[tuple[str, str, object]] = []
        self.uploads: list[tuple[str, str, dict[str, str]]] = []

    def call(self, method: str, path: str, payload=None, content_type=None):
        self.calls.append((method, path, payload))
        if method == "GET" and path.endswith("?page=0&size=100"):
            items = [{
                "id": "skill-1",
                "code": "article-writer",
                **self.media,
            }] if self.existing else []
            return 200, {"items": items}
        if method == "GET" and path.endswith("/skill-1"):
            return 200, {
                "versions": [{
                    "id": "version-1",
                    "version": "1.0.0",
                    "revision": self.revision,
                }],
            }
        if method == "POST" and path == "/console/gateway/admin/skills":
            self.existing = True
            return 201, {"id": "skill-1"}
        if method == "POST" and path.endswith("/version-1/publish"):
            return 200, {"id": "version-1", "revision": self.revision}
        raise AssertionError(f"未预期的调用: {method} {path}")

    def upload(
            self,
            path: str,
            file_path: Path,
            fields: dict[str, str],
            *,
            method: str = "POST"):
        self.uploads.append((method, path, dict(fields)))
        if method == "POST" and path.endswith("/versions"):
            self.revision = 1
            return 200, {
                "id": "version-1",
                "version": "1.0.0",
                "revision": self.revision,
            }
        if method == "PUT" and path.endswith("/version-1/package"):
            assert self.revision is not None
            self.revision += 1
            return 200, {
                "id": "version-1",
                "version": "1.0.0",
                "revision": self.revision,
            }
        media_fields = {
            "/icon": "iconUrl",
            "/illustration": "illustrationUrl",
            "/example-image": "exampleImageUrl",
        }
        for suffix, field in media_fields.items():
            if method == "POST" and path.endswith(suffix):
                if self.media_status != 200:
                    return self.media_status, {"code": "media_restore_failed"}
                self.media[field] = path
                return 200, {field: path}
        raise AssertionError(f"未预期的上传: {method} {path}")


class FakeExpertClient:
    """Small HTTP double covering aggregate Expert restore branches."""

    def __init__(
            self,
            existing_revision: int | None,
            *,
            profile: dict | None = None,
            sealed: bool = False,
            media: dict[str, str | None] | None = None,
            aggregate_status: int = 200,
            package_status: int = 200,
            publish_status: int = 200,
            current_tags: list[str] | None = None,
            served_media: dict[str, tuple[bytes, str]] | None = None,
            code: str = "ai-office-assistant") -> None:
        self.existing = existing_revision is not None
        self.revision = existing_revision
        self.profile = profile
        self.sealed = sealed
        self.version_status = "PUBLISHED" if existing_revision is not None else None
        self.aggregate_status = aggregate_status
        self.package_status = package_status
        self.publish_status = publish_status
        self.current_tags = None if current_tags is None else list(current_tags)
        self.code = code
        self.served_media = dict(served_media or {})
        self.media_reads: list[str] = []
        self.media = dict(media or {})
        self.row_version = 7
        self.calls: list[tuple[str, str, object]] = []
        self.uploads: list[tuple[str, str, dict[str, str]]] = []
        self.aggregate_uploads: list[tuple[str, str, dict[str, str], dict[str, Path]]] = []

    def _version(self) -> dict:
        return {
            "id": "version-1",
            "version": "1.0.0",
            "revision": self.revision,
            "sealed": self.sealed,
            "status": self.version_status,
        }

    def call(self, method: str, path: str, payload=None, content_type=None):
        self.calls.append((method, path, payload))
        if method == "GET" and path.endswith("?page=0&size=100"):
            item = {
                "id": "skill-1",
                "code": self.code,
                "rowVersion": self.row_version,
                "status": "PUBLISHED",
                **self.media,
            }
            if self.current_tags is not None:
                item["tags"] = list(self.current_tags)
            items = [item] if self.existing else []
            return 200, {"items": items}
        if method == "GET" and path.endswith("/expert-presentation"):
            return 200, {"presentation": self.profile}
        if method == "GET" and path.endswith("/skill-1"):
            return 200, {
                "rowVersion": self.row_version,
                "versions": [self._version()],
            }
        if method == "PUT" and path.endswith("/skill-1"):
            self.current_tags = list(payload["tags"])
            self.row_version += 1
            return 200, {"tags": list(self.current_tags), "rowVersion": self.row_version}
        if method == "POST" and path.endswith("/portable-revisions"):
            assert self.revision is not None
            self.revision += 1
            # The generated successor is READY and therefore unsealed; a later gap step may use
            # the ordinary replacement endpoint against this new base.
            self.sealed = False
            self.version_status = "READY"
            return 201, self._version()
        if method == "POST" and path.endswith("/publish"):
            if self.publish_status != 200:
                return self.publish_status, {"code": "publish_failed"}
            self.version_status = "PUBLISHED"
            return 200, self._version()
        raise AssertionError(f"未预期的调用: {method} {path}")

    def get_bytes(self, path: str) -> tuple[int, bytes, str | None]:
        self.media_reads.append(path)
        if path not in self.served_media:
            return 404, b"", None
        content, content_type = self.served_media[path]
        return 200, content, content_type

    def upload(
            self,
            path: str,
            file_path: Path,
            fields: dict[str, str],
            *,
            method: str = "POST"):
        self.uploads.append((method, path, dict(fields)))
        if method == "POST" and path.endswith("/versions"):
            self.existing = True
            self.revision = 1
            self.version_status = "READY"
            return 201, self._version()
        if method == "PUT" and path.endswith("/package"):
            if self.package_status != 200:
                return self.package_status, {"code": "package_restore_failed"}
            assert self.revision is not None
            self.revision += 1
            return 200, self._version()
        raise AssertionError(f"未预期的上传: {method} {path}")

    def upload_many(
            self,
            path: str,
            fields: dict[str, str],
            files: dict[str, Path],
            *,
            method: str = "PUT"):
        self.aggregate_uploads.append((method, path, dict(fields), dict(files)))
        if path.endswith("/experts"):
            self.existing = True
            self.revision = None
            return 201, {"skillId": "skill-1"}
        if path.endswith("/expert-presentation"):
            if self.aggregate_status not in (200, 201):
                return self.aggregate_status, {"code": "expert_restore_failed"}
            data = json.loads(fields["data"])
            self.profile = {
                "taskTypes": data.get("taskTypes"),
                "taskTypesEn": data.get("taskTypesEn"),
                "usageGuide": data.get("usageGuide"),
                "usageGuideEn": data.get("usageGuideEn"),
                "examplePrompts": data.get("examplePrompts"),
                "examplePromptsEn": data.get("examplePromptsEn"),
                "preview": data.get("preview"),
                "previewEn": data.get("previewEn"),
                "canvas": data.get("canvas"),
            }
            self.row_version += 1
            return 200, {
                "skillId": "skill-1",
                "skill": {"rowVersion": self.row_version},
                "presentation": {**self.profile, "rowVersion": self.row_version},
            }
        raise AssertionError(f"未预期的 aggregate 上传: {method} {path}")


def complete_expert_profile(row: dict) -> dict:
    profile = row["expert_presentation"]
    result = {
        "taskTypes": profile["taskTypes"],
        "taskTypesEn": profile["taskTypesEn"],
        "usageGuide": profile["usageGuide"],
        "usageGuideEn": profile["usageGuideEn"],
        "examplePrompts": profile["examplePrompts"],
        "examplePromptsEn": profile["examplePromptsEn"],
        "preview": profile["preview"],
        "previewEn": profile["previewEn"],
    }
    if "canvas" in profile:
        result["canvas"] = profile["canvas"]
    return result


class RevisionPlanTest(unittest.TestCase):
    def test_only_plans_strictly_monotonic_steps(self) -> None:
        self.assertEqual((2, 3), restore.pending_revisions(1, 3))
        self.assertEqual((), restore.pending_revisions(3, 3))
        self.assertEqual((), restore.pending_revisions(4, 3))

    def test_rejects_invalid_revisions(self) -> None:
        with self.assertRaises(ValueError):
            restore.pending_revisions(0, 2)
        with self.assertRaises(ValueError):
            restore.pending_revisions(1, True)

    def test_detail_version_selects_highest_revision_independent_of_api_order(self) -> None:
        class DetailClient:
            def call(self, method: str, path: str, payload=None, content_type=None):
                self.assert_request(method, path)
                return 200, {"versions": [
                    {"id": "ready-3", "version": "1.0.0", "revision": 3,
                     "status": "READY"},
                    {"id": "published-2", "version": "1.0.0", "revision": 2,
                     "status": "PUBLISHED"},
                ]}

            @staticmethod
            def assert_request(method: str, path: str) -> None:
                if method != "GET" or not path.endswith("/skill-1"):
                    raise AssertionError((method, path))

        selected = restore.detail_version(DetailClient(), "skill-1", "1.0.0")
        self.assertIsNotNone(selected)
        self.assertEqual("ready-3", selected["id"])

    def test_profile_complete_requires_all_preview_required_fields(self) -> None:
        row = expert_snapshot_row()
        profile = complete_expert_profile(row)
        self.assertTrue(restore.profile_complete(profile))
        for field in ("heading", "request", "outputTitle"):
            incomplete = json.loads(json.dumps(profile))
            incomplete["preview"].pop(field)
            self.assertFalse(restore.profile_complete(incomplete), field)

        incomplete = json.loads(json.dumps(profile))
        incomplete["preview"]["outputParagraphs"] = [""]
        self.assertFalse(restore.profile_complete(incomplete))

    def test_expert_tags_and_business_task_types_are_separate(self) -> None:
        row = expert_snapshot_row()
        payload = restore.expert_aggregate_payload(row)
        self.assertEqual(["ycps"], payload["tags"])
        self.assertEqual(row["task_types"], payload["taskTypes"])
        self.assertNotIn(row["task_types"][0], payload["tags"])


class RestoreCatalogIdempotencyTest(unittest.TestCase):
    def test_matching_category_cover_is_not_uploaded_again(self) -> None:
        catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
        row = next(item for item in catalog["categories"] if item.get("image_file"))
        image = restore.SNAPSHOT / row["image_file"]

        class Client:
            def __init__(self) -> None:
                self.uploads: list[str] = []

            def call(self, method: str, path: str, payload=None, content_type=None):
                if method == "GET" and "/categories?kind=" in path:
                    kind = path.rsplit("=", 1)[-1]
                    return 200, [row] if kind == row["kind"] else []
                raise AssertionError(f"unexpected call: {method} {path}")

            def get_bytes(self, path: str):
                return 200, image.read_bytes(), restore.media_type_of(image)

            def upload(self, path: str, file_path: Path, fields: dict, *, method="POST"):
                self.uploads.append(path)
                return 200, {}

        client = Client()
        restore.restore_categories(client, {"categories": [row]}, dry=False)
        self.assertEqual([], client.uploads)

    def test_matching_roster_is_not_replaced_again(self) -> None:
        roster = [{
            "code": "ai-office-assistant",
            "member_key": "main",
            "name": "main",
            "name_en": "main",
            "profession": None,
            "profession_en": None,
            "member_role": "LEAD",
            "sort_order": 0,
        }]

        class Client:
            def __init__(self) -> None:
                self.puts: list[object] = []

            def call(self, method: str, path: str, payload=None, content_type=None):
                if method == "GET" and path.endswith("?page=0&size=100"):
                    return 200, {"items": [{"id": "skill-1", "code": roster[0]["code"]}]}
                if method == "GET" and path.endswith("/skill-1"):
                    return 200, {"members": [{
                        "id": "generated-id",
                        "avatarUrl": "/ignored",
                        "memberKey": "main",
                        "name": "main",
                        "nameEn": "main",
                        "profession": None,
                        "professionEn": None,
                        "role": "LEAD",
                        "sortOrder": 0,
                    }]}
                if method == "PUT" and path.endswith("/members"):
                    self.puts.append(payload)
                    return 200, {}
                raise AssertionError(f"unexpected call: {method} {path}")

        client = Client()
        restore.restore_members(client, {"members": roster}, dry=False)
        self.assertEqual([], client.puts)

    def test_clean_environment_dry_run_plans_roster_after_new_expert(self) -> None:
        class Client:
            def __init__(self) -> None:
                self.calls: list[tuple[str, str]] = []

            def call(self, method: str, path: str, payload=None, content_type=None):
                self.calls.append((method, path))
                if method == "GET" and path.endswith("?page=0&size=100"):
                    return 200, {"items": []}
                raise AssertionError(f"unexpected call: {method} {path}")

        client = Client()
        snapshot = {
            "skills": [{"code": "new-imported-expert"}],
            "members": [{"code": "new-imported-expert", "member_key": "lead"}],
        }
        output = io.StringIO()
        with redirect_stdout(output):
            restore.restore_members(client, snapshot, dry=True)

        self.assertIn("花名册 new-imported-expert (1 人)", output.getvalue())
        self.assertIn("dry-run", output.getvalue())
        self.assertEqual(1, len(client.calls))

    def test_missing_roster_target_still_fails_for_real_restore_or_unknown_dry_plan(self) -> None:
        class Client:
            def call(self, method: str, path: str, payload=None, content_type=None):
                if method == "GET" and path.endswith("?page=0&size=100"):
                    return 200, {"items": []}
                raise AssertionError(f"unexpected call: {method} {path}")

        roster = [{"code": "new-imported-expert", "member_key": "lead"}]
        for dry, planned in ((False, True), (True, False)):
            with self.subTest(dry=dry, planned=planned):
                snapshot = {
                    "skills": [{"code": "new-imported-expert"}] if planned else [],
                    "members": roster,
                }
                with self.assertRaisesRegex(RuntimeError, "花名册目标技能缺失"):
                    restore.restore_members(Client(), snapshot, dry=dry)


class RestoreSkillsRevisionTest(unittest.TestCase):
    def test_fresh_restore_replaces_until_snapshot_revision_before_publish(self) -> None:
        client = FakeClient(existing_revision=None)

        restore.restore_skills(client, {"skills": [snapshot_row(3)]}, dry=False)

        self.assertEqual(3, client.revision)
        self.assertEqual(["POST", "PUT", "PUT"], [call[0] for call in client.uploads])
        publish_calls = [
            call for call in client.calls
            if call[0] == "POST" and call[1].endswith("/version-1/publish")
        ]
        self.assertEqual(1, len(publish_calls))

    def test_existing_same_version_is_advanced_without_an_extra_publish(self) -> None:
        client = FakeClient(existing_revision=1)

        restore.restore_skills(client, {"skills": [snapshot_row(3)]}, dry=False)

        self.assertEqual(3, client.revision)
        self.assertEqual(["PUT", "PUT"], [call[0] for call in client.uploads])
        self.assertTrue(all(
            call[1].endswith("/skill-1/versions/version-1/package")
            for call in client.uploads))
        self.assertEqual([], [call for call in client.calls if call[0] != "GET"])

    def test_existing_equal_or_newer_revision_is_not_overwritten(self) -> None:
        for current_revision in (2, 4):
            with self.subTest(current_revision=current_revision):
                client = FakeClient(existing_revision=current_revision)

                restore.restore_skills(client, {"skills": [snapshot_row(2)]}, dry=False)

                self.assertEqual(current_revision, client.revision)
                self.assertEqual([], client.uploads)
                self.assertEqual([], [call for call in client.calls if call[0] != "GET"])

    def test_existing_skill_backfills_only_missing_snapshot_media(self) -> None:
        client = FakeClient(existing_revision=2, media={
            "iconUrl": "/existing/icon",
            "illustrationUrl": "/existing/illustration",
            "exampleImageUrl": None,
        })
        row = snapshot_row(2)
        row.update({
            "icon_file": "images/skills/article-writer-icon.png",
            "illustration_file": "images/skills/article-writer-illustration.png",
            "example_image_file": "images/skills/article-writer-example.png",
        })

        restore.restore_skills(client, {"skills": [row]}, dry=False)

        self.assertEqual(1, len(client.uploads))
        method, path, fields = client.uploads[0]
        self.assertEqual("POST", method)
        self.assertTrue(path.endswith("/skill-1/example-image"))
        self.assertEqual({}, fields)
        self.assertEqual("/existing/icon", client.media["iconUrl"])
        self.assertEqual("/existing/illustration", client.media["illustrationUrl"])

    def test_media_failure_aborts_instead_of_being_reported_as_success(self) -> None:
        client = FakeClient(
            existing_revision=2,
            media={
                "iconUrl": "/existing/icon",
                "illustrationUrl": "/existing/illustration",
                "exampleImageUrl": None,
            },
            media_status=503,
        )
        row = snapshot_row(2)
        row.update({
            "icon_file": "images/skills/article-writer-icon.png",
            "illustration_file": "images/skills/article-writer-illustration.png",
            "example_image_file": "images/skills/article-writer-example.png",
        })

        with self.assertRaisesRegex(RuntimeError, "example_image"):
            restore.restore_skills(client, {"skills": [row]}, dry=False)


class RestoreExpertAggregateTest(unittest.TestCase):
    def test_new_imported_expert_without_example_image_clears_optional_role(self) -> None:
        row = imported_expert_snapshot_row()
        row["example_image_file"] = None
        client = FakeExpertClient(existing_revision=None, code=row["code"])

        restore.restore_skills(client, {"skills": [row]}, dry=False)

        self.assertEqual(1, len(client.aggregate_uploads))
        _, _, fields, files = client.aggregate_uploads[0]
        payload = json.loads(fields["data"])
        self.assertEqual([], payload["tags"])
        self.assertEqual("REPLACE", payload["iconAction"])
        self.assertEqual("REPLACE", payload["illustrationAction"])
        self.assertEqual("CLEAR", payload["exampleImageAction"])
        self.assertEqual({"icon", "illustration"}, set(files))
        self.assertEqual("PUBLISHED", client.version_status)

    def test_incomplete_imported_expert_without_example_image_clears_stale_role(self) -> None:
        row = imported_expert_snapshot_row()
        row["example_image_file"] = None
        client = FakeExpertClient(
            existing_revision=1,
            profile=None,
            code=row["code"],
            media={"exampleImageUrl": "/existing/example-image"},
        )

        restore.restore_skills(client, {"skills": [row]}, dry=False)

        self.assertEqual(1, len(client.aggregate_uploads))
        _, _, fields, files = client.aggregate_uploads[0]
        payload = json.loads(fields["data"])
        self.assertEqual("CLEAR", payload["exampleImageAction"])
        self.assertEqual({"icon", "illustration"}, set(files))

    def test_new_imported_expert_keeps_empty_tags_and_png_media(self) -> None:
        row = imported_expert_snapshot_row()
        client = FakeExpertClient(existing_revision=None, code=row["code"])

        restore.restore_skills(client, {"skills": [row]}, dry=False)

        self.assertEqual(1, len(client.aggregate_uploads))
        _, _, fields, files = client.aggregate_uploads[0]
        payload = json.loads(fields["data"])
        self.assertEqual([], payload["tags"])
        self.assertEqual(row["task_types"], payload["taskTypes"])
        self.assertEqual({"image/png"}, {restore.media_type_of(path) for path in files.values()})
        self.assertEqual("application/zip", restore.media_type_of(
            restore.SNAPSHOT / row["package_file"]))
        self.assertEqual("PUBLISHED", client.version_status)

    def test_existing_imported_expert_restores_empty_tags_without_rewriting_profile(self) -> None:
        row = imported_expert_snapshot_row()
        client = FakeExpertClient(
            existing_revision=1,
            profile=complete_expert_profile(row),
            current_tags=["old-tag"],
            code=row["code"],
        )

        restore.restore_skills(client, {"skills": [row]}, dry=False)

        self.assertEqual([], client.current_tags)
        self.assertEqual([], client.aggregate_uploads)
        updates = [call for call in client.calls if call[0] == "PUT"]
        self.assertEqual(1, len(updates))
        self.assertEqual([], updates[0][2]["tags"])

    def test_incomplete_imported_expert_preserves_snapshot_tags_separate_from_task_types(self) -> None:
        row = imported_expert_snapshot_row(["specific-topic"])
        client = FakeExpertClient(
            existing_revision=1,
            profile=None,
            current_tags=["old-tag"],
            code=row["code"],
        )

        restore.restore_skills(client, {"skills": [row]}, dry=False)

        self.assertEqual(1, len(client.aggregate_uploads))
        payload = json.loads(client.aggregate_uploads[0][2]["data"])
        self.assertEqual(["specific-topic"], payload["tags"])
        self.assertEqual(row["task_types"], payload["taskTypes"])
        self.assertNotIn(row["task_types"][0], payload["tags"])

    def test_expert_snapshot_tags_must_be_valid_and_curated_tags_remain_fixed(self) -> None:
        malformed = (None, "topic", [1], [""], [" topic "], ["x" * 65],
                     ["duplicate", "duplicate"], ["tag"] * 17)
        for tags in malformed:
            with self.subTest(tags=tags):
                row = imported_expert_snapshot_row()
                row["tags"] = tags
                client = FakeExpertClient(existing_revision=None, code=row["code"])
                with self.assertRaisesRegex(RuntimeError, "快照 tags"):
                    restore.restore_skills(client, {"skills": [row]}, dry=False)
                self.assertEqual([], client.aggregate_uploads)
                self.assertEqual([], client.uploads)

        curated = expert_snapshot_row()
        curated["tags"] = []
        with self.assertRaisesRegex(RuntimeError, "必须为"):
            restore.snapshot_expert_tags(curated)

    def test_new_expert_uses_one_atomic_create_before_package(self) -> None:
        row = expert_snapshot_row(3)
        client = FakeExpertClient(existing_revision=None)

        restore.restore_skills(client, {"skills": [row]}, dry=False)

        self.assertEqual(3, client.revision)
        self.assertEqual(1, len(client.aggregate_uploads))
        method, path, fields, files = client.aggregate_uploads[0]
        self.assertEqual("POST", method)
        self.assertTrue(path.endswith("/skills/experts"))
        payload = json.loads(fields["data"])
        self.assertEqual("REPLACE", payload["iconAction"])
        self.assertEqual("REPLACE", payload["illustrationAction"])
        self.assertEqual("REPLACE", payload["exampleImageAction"])
        self.assertEqual(row["task_types"], payload["taskTypes"])
        self.assertEqual({"icon", "illustration", "exampleImage"}, set(files))
        self.assertEqual(["POST", "PUT", "PUT"], [call[0] for call in client.uploads])
        self.assertEqual(1, len([call for call in client.calls if call[1].endswith("/publish")]))

    def test_incomplete_existing_expert_restores_profile_and_all_portable_media_atomically(self) -> None:
        row = expert_snapshot_row(3)
        client = FakeExpertClient(
            existing_revision=2,
            profile=None,
            media={"iconUrl": "/existing/icon", "illustrationUrl": "/existing/illustration"},
        )

        restore.restore_skills(client, {"skills": [row]}, dry=False)

        self.assertEqual(1, len(client.aggregate_uploads))
        method, path, fields, files = client.aggregate_uploads[0]
        self.assertEqual("PUT", method)
        self.assertTrue(path.endswith("/skill-1/expert-presentation"))
        self.assertEqual({"icon", "illustration", "exampleImage"}, set(files))
        payload = json.loads(fields["data"])
        self.assertEqual(7, payload["expectedRowVersion"])
        self.assertEqual("REPLACE", payload["iconAction"])
        self.assertEqual("REPLACE", payload["illustrationAction"])
        self.assertEqual("REPLACE", payload["exampleImageAction"])
        self.assertEqual(3, client.revision)

    def test_incomplete_preview_is_repaired_atomically(self) -> None:
        row = expert_snapshot_row(3)
        profile = complete_expert_profile(row)
        profile["preview"].pop("heading")
        client = FakeExpertClient(existing_revision=3, profile=profile)

        restore.restore_skills(client, {"skills": [row]}, dry=False)

        self.assertEqual(1, len(client.aggregate_uploads))
        payload = json.loads(client.aggregate_uploads[0][2]["data"])
        self.assertEqual("REPLACE", payload["iconAction"])
        self.assertEqual("REPLACE", payload["illustrationAction"])
        self.assertEqual("REPLACE", payload["exampleImageAction"])

    def test_completed_admin_profile_is_never_overwritten(self) -> None:
        row = expert_snapshot_row(3)
        client = FakeExpertClient(
            existing_revision=3,
            profile=complete_expert_profile(row),
        )

        restore.restore_skills(client, {"skills": [row]}, dry=False)

        self.assertEqual([], client.aggregate_uploads)
        self.assertEqual([], client.uploads)
        self.assertEqual([], [call for call in client.calls if call[0] != "GET"])

    def test_explicit_curated_media_repair_is_hash_idempotent(self) -> None:
        row = expert_snapshot_row(3)
        media = served_expert_media(row)
        client = FakeExpertClient(
            existing_revision=3,
            profile=complete_expert_profile(row),
            current_tags=["ycps"],
            served_media=media,
        )

        restore.restore_skills(
            client,
            {"skills": [row]},
            dry=False,
            repair_curated_media=True,
        )

        self.assertEqual([], client.aggregate_uploads)
        self.assertEqual(3, len(client.media_reads))

    def test_explicit_curated_media_repair_replaces_atomic_asset_trio(self) -> None:
        row = expert_snapshot_row(3)
        profile = complete_expert_profile(row)
        profile["usageGuideEn"] = "Administrator-authored English guide"
        media = served_expert_media(row)
        media[f"/v1/skills/{row['code']}/illustration"] = (
            b"legacy-png", "image/png")
        client = FakeExpertClient(
            existing_revision=3,
            profile=profile,
            current_tags=["ycps"],
            served_media=media,
            media={"name": "Administrator title", "summary": "Administrator summary"},
        )

        restore.restore_skills(
            client,
            {"skills": [row]},
            dry=False,
            repair_curated_media=True,
        )

        self.assertEqual(1, len(client.aggregate_uploads))
        _, path, fields, files = client.aggregate_uploads[0]
        self.assertTrue(path.endswith("/skill-1/expert-presentation"))
        self.assertEqual({"icon", "illustration", "exampleImage"}, set(files))
        payload = json.loads(fields["data"])
        self.assertEqual("REPLACE", payload["iconAction"])
        self.assertEqual("REPLACE", payload["illustrationAction"])
        self.assertEqual("REPLACE", payload["exampleImageAction"])
        self.assertEqual("Administrator-authored English guide", payload["usageGuideEn"])
        self.assertEqual("Administrator title", payload["name"])
        self.assertEqual("Administrator summary", payload["summary"])

    def test_explicit_curated_media_repair_replaces_matching_bytes_with_wrong_mime(self) -> None:
        row = expert_snapshot_row(3)
        media = served_expert_media(row)
        route = f"/v1/skills/{row['code']}/icon"
        content, _ = media[route]
        media[route] = (content, "image/jpeg")
        client = FakeExpertClient(
            existing_revision=3,
            profile=complete_expert_profile(row),
            current_tags=["ycps"],
            served_media=media,
        )

        restore.restore_skills(
            client,
            {"skills": [row]},
            dry=False,
            repair_curated_media=True,
        )

        self.assertEqual(1, len(client.aggregate_uploads))
        payload = json.loads(client.aggregate_uploads[0][2]["data"])
        self.assertEqual("REPLACE", payload["iconAction"])
        self.assertEqual("REPLACE", payload["illustrationAction"])
        self.assertEqual("REPLACE", payload["exampleImageAction"])

    def test_completed_admin_profile_repairs_only_operational_tags(self) -> None:
        row = expert_snapshot_row(3)
        client = FakeExpertClient(
            existing_revision=3,
            profile=complete_expert_profile(row),
            current_tags=["business-task-type"],
        )

        restore.restore_skills(client, {"skills": [row]}, dry=False)

        self.assertEqual(["ycps"], client.current_tags)
        self.assertEqual([], client.aggregate_uploads)
        tag_updates = [
            call for call in client.calls
            if call[0] == "PUT" and call[1].endswith("/skill-1")
        ]
        self.assertEqual(1, len(tag_updates))
        self.assertEqual(["ycps"], tag_updates[0][2]["tags"])

    def test_completed_profile_restores_missing_snapshot_canvas_without_replacing_media(self) -> None:
        row = expert_snapshot_row()
        profile = complete_expert_profile(row)
        profile.pop("canvas")
        client = FakeExpertClient(
            existing_revision=row["revision"],
            profile=profile,
            current_tags=["business-task-type"],
        )

        restore.restore_skills(client, {"skills": [row]}, dry=False)

        self.assertEqual(1, len(client.aggregate_uploads))
        method, path, fields, files = client.aggregate_uploads[0]
        self.assertEqual("PUT", method)
        self.assertTrue(path.endswith("/expert-presentation"))
        self.assertEqual({}, files)
        payload = json.loads(fields["data"])
        self.assertEqual(8, payload["expectedRowVersion"])
        self.assertEqual(row["expert_presentation"]["canvas"], payload["canvas"])
        self.assertEqual("KEEP", payload["iconAction"])
        self.assertEqual("KEEP", payload["illustrationAction"])
        self.assertEqual("KEEP", payload["exampleImageAction"])
        self.assertEqual(["ycps"], payload["tags"])
        self.assertEqual(["ycps"], client.current_tags)
        self.assertEqual(row["expert_presentation"]["canvas"], client.profile["canvas"])

    def test_non_null_admin_canvas_is_not_overwritten_by_restore(self) -> None:
        row = expert_snapshot_row()
        profile = complete_expert_profile(row)
        profile["canvas"] = {"zh": {"version": 1, "width": 1080, "height": 704,
                                     "nodes": []}}
        client = FakeExpertClient(
            existing_revision=row["revision"],
            profile=profile,
            current_tags=["ycps"],
        )

        restore.restore_skills(client, {"skills": [row]}, dry=False)

        self.assertEqual([], client.aggregate_uploads)
        self.assertEqual([], [call for call in client.calls if call[0] != "GET"])

    def test_failed_expert_aggregate_aborts_restore_instead_of_reporting_success(self) -> None:
        row = expert_snapshot_row(3)
        client = FakeExpertClient(existing_revision=2, profile=None, aggregate_status=500)

        with self.assertRaisesRegex(RuntimeError, "ai-office-assistant"):
            restore.restore_skills(client, {"skills": [row]}, dry=False)

        self.assertEqual([], client.uploads)
        self.assertEqual(1, len(client.aggregate_uploads))

    def test_sealed_existing_expert_uses_portable_revision_endpoint(self) -> None:
        row = expert_snapshot_row(3)
        client = FakeExpertClient(
            existing_revision=2,
            profile=complete_expert_profile(row),
            sealed=True,
        )

        restore.restore_skills(client, {"skills": [row]}, dry=False)

        self.assertEqual(3, client.revision)
        self.assertEqual([], client.uploads)
        portable = [
            call for call in client.calls
            if call[0] == "POST" and call[1].endswith("/portable-revisions")
        ]
        self.assertEqual(1, len(portable))
        published = [
            call for call in client.calls
            if call[0] == "POST" and call[1].endswith("/publish")
        ]
        self.assertEqual(1, len(published))
        self.assertEqual([], client.aggregate_uploads)

    def test_sealed_base_then_unsealed_successor_converges_a_revision_gap(self) -> None:
        row = expert_snapshot_row(4)
        client = FakeExpertClient(
            existing_revision=2,
            profile=complete_expert_profile(row),
            sealed=True,
        )

        restore.restore_skills(client, {"skills": [row]}, dry=False)

        self.assertEqual(4, client.revision)
        portable = [
            call for call in client.calls
            if call[0] == "POST" and call[1].endswith("/portable-revisions")
        ]
        self.assertEqual(1, len(portable))
        self.assertEqual(["PUT"], [call[0] for call in client.uploads])
        self.assertEqual("PUBLISHED", client.version_status)

    def test_higher_ready_expert_revision_is_not_promoted_or_downgraded(self) -> None:
        row = expert_snapshot_row(3)
        client = FakeExpertClient(
            existing_revision=4,
            profile=complete_expert_profile(row),
            sealed=False,
        )
        client.version_status = "READY"

        restore.restore_skills(client, {"skills": [row]}, dry=False)

        self.assertEqual(4, client.revision)
        self.assertEqual("READY", client.version_status)
        self.assertEqual([], client.uploads)
        self.assertEqual([], [
            call for call in client.calls
            if call[0] == "POST" and call[1].endswith("/publish")
        ])

    def test_failed_expert_package_aborts_without_silent_success(self) -> None:
        row = expert_snapshot_row(3)
        client = FakeExpertClient(
            existing_revision=2,
            profile=complete_expert_profile(row),
            package_status=503,
        )

        with self.assertRaisesRegex(RuntimeError, "Expert package restore failed"):
            restore.restore_skills(client, {"skills": [row]}, dry=False)

    def test_failed_expert_publish_aborts(self) -> None:
        row = expert_snapshot_row(3)
        client = FakeExpertClient(
            existing_revision=3,
            profile=complete_expert_profile(row),
            sealed=True,
            publish_status=503,
        )
        client.version_status = "READY"

        with self.assertRaisesRegex(RuntimeError, "Expert package publish failed"):
            restore.restore_skills(client, {"skills": [row]}, dry=False)

    def test_ready_target_revision_is_published_after_an_interrupted_restore(self) -> None:
        row = expert_snapshot_row(3)
        client = FakeExpertClient(
            existing_revision=3,
            profile=complete_expert_profile(row),
            sealed=True,
        )
        client.version_status = "READY"

        restore.restore_skills(client, {"skills": [row]}, dry=False)

        self.assertEqual("PUBLISHED", client.version_status)
        self.assertEqual([], client.aggregate_uploads)
        self.assertEqual([], client.uploads)
        self.assertEqual(1, len([
            call for call in client.calls
            if call[0] == "POST" and call[1].endswith("/publish")
        ]))


if __name__ == "__main__":
    unittest.main()
