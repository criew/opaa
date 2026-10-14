"""Self-check for check_code_comments.sh: each rule catches its form in every checked tree, and the
forms the rule allows (an upstream reference, an HTML entity, the spike package, a string outside a
comment or docstring, a test's regression guard) pass."""

import shutil
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parent / "check_code_comments.sh"
BASH = shutil.which("bash") or "bash"
JAVA = "backend/src/main/java/io/opaa/probe/Probe.java"


def run(repo: Path) -> subprocess.CompletedProcess:
    subprocess.run(["git", "add", "-A"], cwd=repo, check=True)
    return subprocess.run([BASH, SCRIPT.as_posix()], cwd=repo, capture_output=True, text=True)


def write(repo: Path, path: str, text: str) -> None:
    target = repo / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def java(comment: str) -> str:
    return f"package io.opaa.probe;\n\n/**\n * {comment}\n */\nclass Probe {{\n  String s = \"(#123)\";\n}}\n"


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    subprocess.run(["git", "init", "-q"], cwd=tmp_path, check=True)
    return tmp_path


@pytest.mark.parametrize(
    "comment",
    [
        "A grant opens nothing else (ADR-0036, Entscheidung 5).",
        "Workaround until spring-projects/spring-ai#2042 is fixed upstream.",
        "The path /api/v1/documents/&#123;id&#125;/content stays closed.",
        "See docs/features/spaces-and-assets.md#123 for the rule.",
        "Workaround until #2042 is fixed upstream.",
        "@deprecated Removed after #2500 replaces it.",
        "Spike #1789: a throwaway path.",
    ],
)
def test_allowed_comment_passes(repo: Path, comment: str) -> None:
    write(repo, JAVA, java(comment))
    result = run(repo)
    assert result.returncode == 0, result.stdout


@pytest.mark.parametrize(
    "comment, kind",
    [
        ("#393 code review, finding 1: with three roles a check is needed.", "Review protocol"),
        ("Deliberately not defaulted (PR #700 review, nit 8).", "Review protocol"),
        ("Validated before the write (review of PR #297).", "Review protocol"),
        ("All three come from the same table since #1931.", "History"),
        ("Mirrors the pre-#888 call site.", "History"),
        ("Aborted before #1816 changed it.", "History"),
        ("A spike aside, every library has an owner (#1073).", "Origin marker"),
        ("Every library has an owner (#1073).", "Origin marker"),
        ("The connection log (#2163, ADR-0041): append-only.", "Origin marker"),
        ("#1184: top-level documents only.", "Origin marker"),
        ("Issue #1070: the golden filters move the numbers.", "Origin marker"),
        ("#583/#1910: the sign-in page renders before a session.", "Origin marker"),
        ("A plain boolean cannot check it (#420 second code review round).", "Review protocol"),
        ("Review finding on PR #827: only the unique index closes the race.", "Review protocol"),
        ("Nicht-Admins sehen die Spalte nicht (Review zu #803).", "Review protocol"),
    ],
)
def test_rejected_comment_fails(repo: Path, comment: str, kind: str) -> None:
    write(repo, JAVA, java(comment))
    result = run(repo)
    assert result.returncode == 1
    assert kind in result.stdout


def test_spike_package_is_exempt(repo: Path) -> None:
    write(repo, "backend/src/main/java/io/opaa/query/spike/Spike.java", java("The spike tool (#1789)."))
    assert run(repo).returncode == 0


def test_openapi_description_and_yaml_comment_are_checked(repo: Path) -> None:
    write(repo, JAVA, java("Clean."))
    write(repo, "opaa-api/src/main/openapi/probe.yaml", "x:\n  description: >\n    Paged (#1880).\n")
    assert "probe.yaml" in run(repo).stdout
    (repo / "opaa-api/src/main/openapi/probe.yaml").unlink()
    write(repo, "backend/src/main/resources/application.yml", "a:\n  # #1050: how many candidates\n  b: 1\n")
    result = run(repo)
    assert result.returncode == 1
    assert "application.yml" in result.stdout


TREES = {
    "test": "backend/src/test/java/io/opaa/probe/ProbeTest.java",
    "evalTest": "backend/src/evalTest/java/io/opaa/eval/ProbeTest.java",
}
WORKFLOW = ".github/workflows/probe.yml"
CHANGELOG = "backend/src/main/resources/db/changelog/probe/2026-10-14-probe.yaml"


def workflow(comment: str) -> str:
    return f"on: push\n# {comment}\njobs: {{}}\n"


def changelog(comment: str) -> str:
    return f'databaseChangeLog:\n  - changeSet:\n      id: probe\n      comment: "{comment}"\n'


@pytest.mark.parametrize("tree", TREES)
def test_regression_guard_in_a_test_is_counted_apart(repo: Path, tree: str) -> None:
    write(repo, TREES[tree], java("Regression guard for #307: a stale report never hides the last one."))
    result = run(repo)
    assert result.returncode == 0, result.stdout
    assert f"{tree}: 0 checked lines still name an issue, 1 more as a regression guard" in result.stdout


def test_regression_guard_counts_only_in_a_test(repo: Path) -> None:
    write(repo, JAVA, java("Regression guard for #307: a stale report never hides the last one."))
    assert "main: 1 checked lines still name an issue, 0 more" in run(repo).stdout


@pytest.mark.parametrize(
    "path, text",
    [
        *[(p, java("Regression guard for review of #2438: a 403 is no absence.")) for p in TREES.values()],
        *[(p, java("#393 code review, finding 1: with three roles a check is needed.")) for p in TREES.values()],
        (WORKFLOW, workflow("PR #301 review, Befund 2: the group key names the label.")),
        (WORKFLOW, "on: push\njobs:\n  a:\n    steps:\n      - run: |\n          // Provenance check (PR #301 review): only our marker\n"),
        (CHANGELOG, "databaseChangeLog:\n  # #1820 review: the count goes\n"),
    ],
)
def test_review_protocol_fails_in_every_tree(repo: Path, path: str, text: str) -> None:
    write(repo, path, text)
    result = run(repo)
    assert result.returncode == 1
    assert "Review protocol" in result.stdout
    assert path in result.stdout


def test_german_history_fails_in_a_workflow(repo: Path) -> None:
    write(repo, WORKFLOW, workflow("Seit #1040 führt der Lauf zwei Urteile."))
    result = run(repo)
    assert result.returncode == 1
    assert "History" in result.stdout


@pytest.mark.parametrize(
    "path, text",
    [
        *[(p, java("Every library has an owner (#1073).")) for p in TREES.values()],
        (WORKFLOW, workflow("A corpus-only PR triggers no API calls (#1123).")),
        (CHANGELOG, changelog("The connection log (#2163, ADR-0041): append-only.")),
        (CHANGELOG, changelog("#2163: append-only.")),
        (CHANGELOG, 'databaseChangeLog:\n  - changeSet:\n      comment: >-\n        Expression index\n        (#1073) on library_id.\n      runInTransaction: false\n'),
    ],
)
def test_origin_marker_fails_in_every_tree(repo: Path, path: str, text: str) -> None:
    write(repo, path, text)
    result = run(repo)
    assert result.returncode == 1
    assert "Origin marker" in result.stdout
    assert path in result.stdout


@pytest.mark.parametrize(
    "path, text",
    [
        (WORKFLOW, workflow("Workaround until #2042 is fixed upstream.")),
        (CHANGELOG, changelog("The connection log (ADR-0041, Entscheidung 7): append-only.")),
        (CHANGELOG, 'databaseChangeLog:\n  - changeSet:\n      comment: >-\n        Expression index.\n      sql: SELECT 1 -- (#1073)\n'),
    ],
)
def test_allowed_workflow_and_changelog_comment_passes(repo: Path, path: str, text: str) -> None:
    write(repo, path, text)
    result = run(repo)
    assert result.returncode == 0, result.stdout


def test_no_comment_at_all_fails(repo: Path) -> None:
    write(repo, "README.md", "x\n")
    assert run(repo).returncode == 1


def slash(comment: str) -> str:
    return f"/**\n * {comment}\n */\nconst s = '(#123)'\n// Clean.\n"


def hash_line(comment: str) -> str:
    return f"# {comment}\nA=1\n"


def docstring(comment: str) -> str:
    return f'#!/usr/bin/env python3\n"""Probe.\n\n{comment}\n"""\nX = "(#123)"\n'


# (label, path, render): one entry per file type of every tree added beside the Java sources.
FILES = [
    ("e2e", "e2e/tests/probe.spec.ts", slash),
    ("e2e", "e2e/scripts/probe.mjs", lambda c: f"// {c}\nexport {{}}\n"),
    ("e2e", "e2e/probe.env", hash_line),
    ("e2e", "e2e/docker-compose.probe.yml", lambda c: f"services:\n  # {c}\n  a: {{}}\n"),
    ("eval", "eval/generator/probe.py", lambda c: f"# {c}\nX = 1\n"),
    ("eval", "eval/baseline/probe.py", docstring),
    ("build", "docker-compose.yml", lambda c: f"services:\n  # {c}\n  a: {{}}\n"),
    ("build", "backend/Dockerfile", lambda c: f"# {c}\nFROM scratch\n"),
    ("build", "frontend/Dockerfile", lambda c: f"# {c}\nFROM scratch\n"),
    ("build", "backend/gradle/libs.versions.toml", lambda c: f"[versions]\n# {c}\na = \"1\"\n"),
    ("build", "backend/settings.gradle.kts", lambda c: f"// {c}\nrootProject.name = \"opaa\"\n"),
    ("build", "backend/build.gradle.kts", lambda c: f"/*\n * {c}\n */\nplugins {{}}\n"),
    ("build", "opaa-api/build.gradle.kts", lambda c: f"// {c}\nplugins {{}}\n"),
    ("test", "backend/src/test/resources/test-documents/generate-odf-fixtures.py", docstring),
]
FILE_IDS = [path for _, path, _ in FILES]


@pytest.mark.parametrize("label, path, render", FILES, ids=FILE_IDS)
def test_review_protocol_fails_in_every_added_tree(repo: Path, label: str, path: str, render) -> None:
    write(repo, path, render("test(e2e) #760, PR review finding 2: the stub records the model."))
    result = run(repo)
    assert result.returncode == 1
    assert "Review protocol" in result.stdout
    assert path in result.stdout


@pytest.mark.parametrize("label, path, render", FILES, ids=FILE_IDS)
def test_origin_marker_fails_in_every_added_tree(repo: Path, label: str, path: str, render) -> None:
    write(repo, path, render("#1383: the seed triggers both S3 libraries' runs."))
    result = run(repo)
    assert result.returncode == 1
    assert "Origin marker" in result.stdout
    assert path in result.stdout


@pytest.mark.parametrize("label, path, render", FILES, ids=FILE_IDS)
def test_permitted_reference_is_counted_in_every_added_tree(
    repo: Path, label: str, path: str, render
) -> None:
    write(repo, path, render("Workaround until #2042 is fixed upstream."))
    result = run(repo)
    assert result.returncode == 0, result.stdout
    assert f"{label}: 1 checked lines still name an issue, 0 more" in result.stdout


def test_regression_guard_in_e2e_is_counted_apart(repo: Path) -> None:
    write(repo, "e2e/tests/probe.spec.ts", slash("Regression guard for #307: a stale chat never hides."))
    result = run(repo)
    assert result.returncode == 0, result.stdout
    assert "e2e: 0 checked lines still name an issue, 1 more as a regression guard" in result.stdout


@pytest.mark.parametrize(
    "text",
    [
        'X = "(#1073)"\n# Clean.\n',
        '# Clean.\nTEMPLATE = """\n(#1073) is data, not a docstring.\n"""\n',
        '"""One-line docstring."""\nX = "(#1073)"\n',
    ],
)
def test_python_string_outside_a_docstring_passes(repo: Path, text: str) -> None:
    write(repo, "eval/generator/probe.py", text)
    result = run(repo)
    assert result.returncode == 0, result.stdout
