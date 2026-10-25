"""Self-check for check_instant_now.sh: a count above the limit fails with the file list, the count
at the limit passes, a count below it passes with the lower limit; Instant.now(clock), comment
lines, entities, records and classes that are neither annotated as a bean nor built with `new` in a
file with @Configuration or a @Bean method are not counted."""

import os
import shutil
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parent / "check_instant_now.sh"
BASH = shutil.which("bash") or "bash"
ROOT = "backend/src/main/java/io/opaa/probe/"


def run(repo: Path, limit: int) -> subprocess.CompletedProcess:
    subprocess.run(["git", "add", "-A"], cwd=repo, check=True)
    env = {**os.environ, "OPAA_RATCHET_LIMIT": str(limit)}
    return subprocess.run(
        [BASH, SCRIPT.as_posix()], cwd=repo, capture_output=True, text=True, env=env
    )


def write(repo: Path, name: str, text: str) -> None:
    target = repo / ROOT / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    subprocess.run(["git", "init", "-q"], cwd=tmp_path, check=True)
    write(
        tmp_path,
        "ProbeService.java",
        "package io.opaa.probe;\n\n@Service\nclass ProbeService {\n"
        "  // Instant.now() in a comment\n"
        "  Instant a() { return Instant.now(); }\n"
        "  boolean b() { return Instant.now().isAfter(Instant.now()); }\n"
        "  Instant c(Clock clock) { return Instant.now(clock); }\n}\n",
    )
    write(
        tmp_path,
        "ProbeEntity.java",
        "package io.opaa.probe;\n\n@Entity\n@Component\nclass ProbeEntity {\n"
        "  Instant createdAt = Instant.now();\n}\n",
    )
    write(
        tmp_path,
        "ProbeRecord.java",
        "package io.opaa.probe;\n\n@Component\npublic record ProbeRecord(Instant at) {\n"
        "  ProbeRecord() { this(Instant.now()); }\n}\n",
    )
    write(
        tmp_path,
        "ProbePlain.java",
        "package io.opaa.probe;\n\nclass ProbePlain {\n  Instant at = Instant.now();\n}\n",
    )
    return tmp_path


def test_count_at_limit_passes(repo: Path) -> None:
    result = run(repo, 3)
    assert result.returncode == 0, result.stdout
    assert "at the limit of 3" in result.stdout


def test_count_above_limit_fails_with_file_list(repo: Path) -> None:
    result = run(repo, 2)
    assert result.returncode == 1
    assert "::error::3 calls" in result.stdout
    assert f"3 {ROOT}ProbeService.java" in result.stdout
    assert "ProbeEntity" not in result.stdout


def test_count_below_limit_asks_to_lower_it(repo: Path) -> None:
    result = run(repo, 5)
    assert result.returncode == 0, result.stdout
    assert "::notice::" in result.stdout
    assert "lower the limit to 3" in result.stdout


@pytest.mark.parametrize(
    "annotation",
    [
        "@Component",
        "@Configuration",
        "@RestController",
        "@Controller",
        "@RestControllerAdvice",
        "@ControllerAdvice",
    ],
)
def test_every_bean_annotation_counts(repo: Path, annotation: str) -> None:
    write(
        repo,
        "ProbeBean.java",
        f"package io.opaa.probe;\n\n{annotation}\nclass ProbeBean {{\n"
        "  Instant at() { return Instant.now(); }\n}\n",
    )
    result = run(repo, 3)
    assert result.returncode == 1
    assert f"1 {ROOT}ProbeBean.java" in result.stdout


def test_a_class_built_in_a_configuration_counts(repo: Path) -> None:
    write(
        repo,
        "ProbeConfiguration.java",
        "package io.opaa.probe;\n\n@Configuration\nclass ProbeConfiguration {\n"
        "  @Bean\n  Object plain() { return new ProbePlain(); }\n}\n",
    )
    result = run(repo, 3)
    assert result.returncode == 1
    assert "::error::4 calls" in result.stdout
    assert f"1 {ROOT}ProbePlain.java" in result.stdout


def test_a_class_built_outside_a_configuration_or_in_a_comment_does_not_count(
    repo: Path,
) -> None:
    write(
        repo,
        "ProbeFactory.java",
        "package io.opaa.probe;\n\nclass ProbeFactory {\n"
        "  Object plain() { return new ProbePlain(); }\n}\n",
    )
    write(
        repo,
        "ProbeConfiguration.java",
        "package io.opaa.probe;\n\n@Configuration\nclass ProbeConfiguration {\n"
        "  // new ProbePlain() would be built here\n}\n",
    )
    result = run(repo, 3)
    assert result.returncode == 0, result.stdout
    assert "ProbePlain" not in result.stdout


def test_a_class_built_by_a_lite_bean_method_in_a_component_counts(repo: Path) -> None:
    write(
        repo,
        "ProbeSchedule.java",
        "package io.opaa.probe;\n\n@Component\nclass ProbeSchedule {\n"
        "  @Bean\n  Object plain() { return new ProbePlain(); }\n}\n",
    )
    result = run(repo, 3)
    assert result.returncode == 1
    assert "::error::4 calls" in result.stdout
    assert f"1 {ROOT}ProbePlain.java" in result.stdout


def test_a_bean_annotation_only_in_a_comment_makes_no_source(repo: Path) -> None:
    write(
        repo,
        "ProbeFactory.java",
        "package io.opaa.probe;\n\n/**\n * @Bean\n */\nclass ProbeFactory {\n"
        "  // @Configuration\n  Object plain() { return new ProbePlain(); }\n}\n",
    )
    result = run(repo, 3)
    assert result.returncode == 0, result.stdout
    assert "ProbePlain" not in result.stdout
