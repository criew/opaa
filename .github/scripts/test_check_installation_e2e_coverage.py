"""Self-check for check_installation_e2e_coverage.sh: passes when every constant of the outer enum
InstallationStep has a label in STEP_INFO that the spec waits for as a step heading; fails naming
the step when the spec misses it or STEP_INFO has no label; a label may be a key of SETUP_NAMES;
constants of a nested enum are not steps."""

import shutil
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parent / "check_installation_e2e_coverage.sh"
BASH = shutil.which("bash") or "bash"
ENUM = "backend/src/main/java/io/opaa/installation/InstallationStep.java"
STEPS = "frontend/src/features/installation/utils/steps.ts"
SPEC = "e2e/tests/installation-assistant.spec.ts"
NAMES = "frontend/src/shared/ui/setup/setupNames.ts"

ENUM_TEXT = """package io.opaa.installation;

/** The steps. */
public enum InstallationStep {
  /** First. */
  FIRST_ADMIN(true),
  MAIL(false),
  TEXT_RECOGNITION_MODEL(false, Limit.TEXT_RECOGNITION);

  enum Limit {
    SPEECH_INPUT,
    TEXT_RECOGNITION
  }
}
"""

STEPS_TEXT = """export const STEP_INFO = {
  FIRST_ADMIN: { label: 'Erstes Konto', laterUnder: 'Benutzer' },
  MAIL: { label: 'E-Mail-Versand', laterUnder: 'E-Mail' },
  TEXT_RECOGNITION_MODEL: { label: SETUP_NAMES.textRecognitionModel, laterUnder: 'Modelle' },
}
"""

NAMES_TEXT = """export const SETUP_NAMES = {
  mail: 'Post',
  textRecognitionModel: 'Texterkennung',
} as const
"""

SPEC_TEXT = """await expect(stepHeading(page, 'Erstes Konto')).toBeVisible()
await expect(stepHeading(page, 'E-Mail-Versand')).toBeVisible()
await expect(stepHeading(page, 'Texterkennung')).toBeVisible()
"""


def write(repo: Path, path: str, text: str) -> None:
    target = repo / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


def run(repo: Path) -> subprocess.CompletedProcess:
    return subprocess.run([BASH, SCRIPT.as_posix()], cwd=repo, capture_output=True, text=True)


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    subprocess.run(["git", "init", "-q"], cwd=tmp_path, check=True)
    write(tmp_path, ENUM, ENUM_TEXT)
    write(tmp_path, STEPS, STEPS_TEXT)
    write(tmp_path, SPEC, SPEC_TEXT)
    write(tmp_path, NAMES, NAMES_TEXT)
    return tmp_path


def test_every_step_covered_passes(repo: Path) -> None:
    result = run(repo)
    assert result.returncode == 0, result.stdout + result.stderr
    assert "All 3 steps" in result.stdout


def test_step_missing_in_spec_fails(repo: Path) -> None:
    write(repo, SPEC, SPEC_TEXT.replace("'Texterkennung'", "'Anderes'"))
    result = run(repo)
    assert result.returncode == 1
    assert "InstallationStep.TEXT_RECOGNITION_MODEL" in result.stdout
    assert "E-Mail-Versand" not in result.stdout


def test_step_without_label_fails(repo: Path) -> None:
    write(repo, ENUM, ENUM_TEXT.replace("  MAIL(false),\n", "  MAIL(false),\n  BRANDING(false),\n"))
    result = run(repo)
    assert result.returncode == 1
    assert "InstallationStep.BRANDING has no label" in result.stdout


def test_nested_enum_constants_are_no_steps(repo: Path) -> None:
    result = run(repo)
    assert "SPEECH_INPUT" not in result.stdout


def test_label_from_setup_names_is_resolved(repo: Path) -> None:
    write(repo, NAMES, NAMES_TEXT.replace("'Texterkennung'", "'Schrifterkennung'"))
    result = run(repo)
    assert result.returncode == 1
    assert 'Step "Schrifterkennung"' in result.stdout
