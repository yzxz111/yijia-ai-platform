"""Regenerate the AI Learning card through the shared seven-Expert renderer.

This preserves the portable-import fixture's existing rebuild entry point.
"""

from pathlib import Path
import shutil
import subprocess


SYSTEM_ROOT = Path(__file__).resolve().parent.parent
RENDERER = SYSTEM_ROOT / "scripts" / "render-expert-example-images.cjs"


def main() -> None:
    node = shutil.which("node")
    if node is None:
        raise SystemExit("Node.js is required to render the Expert example images")
    subprocess.run(
        [node, str(RENDERER), "--write", "--only", "ai-learning-assistant"],
        cwd=SYSTEM_ROOT,
        check=True,
    )


if __name__ == "__main__":
    main()
