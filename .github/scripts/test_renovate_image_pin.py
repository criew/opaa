"""Self-check for the Renovate image pin in renovate.yml.

The workflow hands a token with repo and workflow scope to the image it starts, so every image
reference carries an exact version and the registry's index digest. A regex manager in
renovate.json5 keeps both current. If the workflow line drifts away from its matchString, Renovate
stops proposing updates without any warning; these cases catch that in the PR that causes it.
"""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
WORKFLOW = ROOT / ".github" / "workflows" / "renovate.yml"
RENOVATE_CONFIG = ROOT / "renovate.json5"
WORKFLOW_PATTERN = "'.github/workflows/renovate.yml'"
PINNED_IMAGE = re.compile(r"renovate/renovate:(\d+\.\d+\.\d+)@(sha256:[0-9a-f]{64})")


def image_references() -> list[str]:
    return re.findall(r"renovate/renovate[:@]\S*", WORKFLOW.read_text(encoding="utf-8"))


def config_blocks() -> list[str]:
    """Innermost {...} blocks of renovate.json5: customManagers and packageRules entries."""
    return re.findall(r"\{[^{}]*\}", RENOVATE_CONFIG.read_text(encoding="utf-8"))


def only_block(*settings: str) -> str:
    blocks = [block for block in config_blocks() if all(s in block for s in settings)]
    assert len(blocks) == 1, f"expected exactly one renovate.json5 block with {settings}"
    return blocks[0]


def manager_regex() -> re.Pattern[str]:
    """The matchString as Renovate reads it, translated to Python's named-group syntax."""
    manager = only_block(f"managerFilePatterns: [{WORKFLOW_PATTERN}]", "customType: 'regex'")
    literal = re.search(r"matchStrings: \[\s*'((?:[^'\\]|\\.)*)'", manager)
    assert literal, "matchStrings entry not found in the custom manager for renovate.yml"
    pattern = re.sub(r"\\(.)", r"\1", literal.group(1))
    return re.compile(pattern.replace("(?<", "(?P<"))


def test_every_image_reference_is_pinned_to_version_and_digest() -> None:
    references = image_references()

    assert references, "renovate.yml no longer references renovate/renovate"
    for reference in references:
        assert PINNED_IMAGE.fullmatch(reference), reference


def test_regex_manager_extracts_the_pinned_version_and_digest() -> None:
    match = manager_regex().search(WORKFLOW.read_text(encoding="utf-8"))

    assert match, "the custom manager for renovate.yml does not match the workflow"
    pinned = PINNED_IMAGE.fullmatch(image_references()[0])
    assert match.group("datasource") == "docker"
    assert match.group("depName") == "renovate/renovate"
    assert match.group("currentValue") == pinned.group(1)
    assert match.group("currentDigest") == pinned.group(2)


def test_updates_wait_three_days_and_never_merge_automatically() -> None:
    rule = only_block(f"matchFileNames: [{WORKFLOW_PATTERN}]", "minimumReleaseAge")

    assert "matchDepNames: ['renovate/renovate']" in rule
    assert "pinDigests: true" in rule
    assert "minimumReleaseAge: '3 days'" in rule
    assert "automerge: false" in rule
    assert "matchUpdateTypes" not in rule


def test_a_new_digest_for_the_same_version_needs_dashboard_approval() -> None:
    rule = only_block(f"matchFileNames: [{WORKFLOW_PATTERN}]", "matchUpdateTypes: ['digest']")

    assert "matchDepNames: ['renovate/renovate']" in rule
    assert "dependencyDashboardApproval: true" in rule
