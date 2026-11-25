# YCPS package sources

This directory contains native YCPS 1.0.0 source roots used to build database imports and immutable
disaster-recovery archives. Upload, download, desktop installation, and runtime activation accept YCPS only.
There is no `skill.json`/root `SKILL.md` fallback.

The authoritative format is
[`docs/skill-expert-team-authoring-standard.md`](../docs/skill-expert-team-authoring-standard.md), with frozen
machine contracts in [`docs/standards/ycps/1.0.0`](../docs/standards/ycps/1.0.0).

Each direct child is one independent immutable package source:

```text
skills/<code>/
├── plugin.json
├── skills/<code>/SKILL.md
└── com.yuzhihuihe.ycps/manifest.json
```

Optional package entries are limited to the locations allowed by the YCPS root allowlist. A package contains
exactly one Entry Skill. Expert and Team behavior is expressed by the kind-specific runtime manifest, not by
placing multiple Skill directories in one package.

Before importing or publishing, validate the source tree and build `<code>-<version>.ycps.zip`.
The standalone `scripts/ycps/*.mjs` authoring toolchain is external to this checkout. If it is available,
set `YUZHI_REPO_ROOT` to its workspace root and run from `system/`:

```powershell
node (Join-Path $env:YUZHI_REPO_ROOT 'scripts/ycps/validate.mjs') --all
node (Join-Path $env:YUZHI_REPO_ROOT 'scripts/ycps/build-package.mjs') skills/<code> <code>-<version>.ycps.zip
```

The batch importer requires that external builder too; pass its absolute path with `-BuilderScript`.
Do not use the old `scripts/build-ycps.py` / `build-all-ycps.py` as portable commands: they contain
historical machine-specific `E:` paths.

Current YCPS 1.1 Expert sources and verified packages live in
[`fixtures/expert-expansion/`](../fixtures/expert-expansion/README.md), following the
[Expert generation standard](../docs/ycps-1.1-expert-generation-standard.md). Their existing spec-driven
builder is part of this checkout; for example, verify a retained package without replacing it:

```powershell
python scripts/build-expert-expansion.py --code academic-writing-mentor --check
```

The uploaded cloud catalog is captured independently in
[`fixtures/catalog-snapshot/`](../fixtures/catalog-snapshot/README.md); the source directories here do
not enumerate the current published catalog.

`GATEWAY_SKILLS_SOURCE=filesystem` does not read these source directories or build archives at runtime. Its root
contains only prebuilt and scanned `*.ycps.zip` artifacts with frozen package and manifest
digests. Database import uses `docs/testing/skill-ycps-import.ps1` and the normal YCPS ingestion pipeline.

Third-party or historical formats must be converted by the offline compatibility layer, reviewed, and validated
as YCPS before they enter this directory or the database. Unsupported input must fail with
`unsupported_package_format`; it must never be interpreted as a runtime package.
