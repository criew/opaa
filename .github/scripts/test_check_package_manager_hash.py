"""Self-check for check_package_manager_hash.sh.

The CI run only exercises the green branch against the repository's own manifests;
a change that makes the guard laxer would go unnoticed there. These cases pin the
rejecting branches instead: missing hash, truncated hash, unreadable value, a nested
occurrence shadowing the real field, and finding no manifest at all.
"""

import json
import shutil
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parent / "check_package_manager_hash.sh"
SHA512 = "a" * 128
SHA256 = "b" * 64
BASH = shutil.which("bash") or "bash"


def run_guard(repo: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [BASH, SCRIPT.as_posix()],
        cwd=repo,
        capture_output=True,
        text=True,
    )


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    subprocess.run(["git", "init", "-q"], cwd=tmp_path, check=True)
    return tmp_path


def add(repo: Path, relative: str, content: str) -> None:
    path = repo / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    subprocess.run(["git", "add", relative], cwd=repo, check=True)


def manifest(package_manager: str | None) -> str:
    data: dict[str, object] = {"name": "demo"}
    if package_manager is not None:
        data["packageManager"] = package_manager
    return json.dumps(data, indent=2) + "\n"


def test_accepts_sha512_and_sha256(repo: Path) -> None:
    add(repo, "frontend/package.json", manifest(f"pnpm@12.4.1+sha512.{SHA512}"))
    add(repo, "e2e/package.json", manifest(f"pnpm@12.4.1+sha256.{SHA256}"))

    result = run_guard(repo)

    assert result.returncode == 0, result.stdout
    assert "All 2 packageManager entries" in result.stdout


def test_rejects_missing_hash_and_names_the_remedy(repo: Path) -> None:
    add(repo, "frontend/package.json", manifest("pnpm@12.4.1"))

    result = run_guard(repo)

    assert result.returncode == 1
    assert "has no Corepack integrity hash" in result.stdout
    assert "corepack use pnpm@12.4.1" in result.stdout
    assert "in frontend" in result.stdout


def test_rejects_truncated_hash(repo: Path) -> None:
    add(repo, "frontend/package.json", manifest("pnpm@12.4.1+sha512."))
    add(repo, "e2e/package.json", manifest(f"pnpm@12.4.1+sha512.{SHA512[:64]}"))

    result = run_guard(repo)

    assert result.returncode == 1
    assert result.stdout.count("malformed Corepack integrity hash") == 2
    assert "2 of 2 packageManager entries" in result.stdout


def test_rejects_unreadable_value_instead_of_skipping_it(repo: Path) -> None:
    add(repo, "frontend/package.json", manifest(f"pnpm@12.4.1+sha512.{SHA512}"))
    add(
        repo,
        "e2e/package.json",
        '{\n  "packageManager":\n    "pnpm@12.4.1"\n}\n',
    )

    result = run_guard(repo)

    assert result.returncode == 1
    assert "could not read its value" in result.stdout
    assert "1 of 2 packageManager entries" in result.stdout


def test_reads_the_first_occurrence_not_a_nested_one(repo: Path) -> None:
    add(
        repo,
        "frontend/package.json",
        '{"packageManager":"pnpm@12.4.1","x":{"packageManager":"pnpm@12.4.1+sha512.'
        + SHA512
        + '"}}\n',
    )

    result = run_guard(repo)

    assert result.returncode == 1
    assert "has no Corepack integrity hash" in result.stdout


def test_fails_when_no_manifest_carries_the_field(repo: Path) -> None:
    add(repo, "frontend/package.json", manifest(None))

    result = run_guard(repo)

    assert result.returncode == 1
    assert "would pass without checking anything" in result.stdout
