"""Self-check for the openCode mirror and publiccode.yml (docs/opencode.md).

The mirror runs only once a maintainer configures it, and openCode reads publiccode.yml only on
its own side, so a forced push or a field out of the standard would surface there at the earliest.
These cases hold the workflow and the metadata file to the rules the document states.
"""

import re
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "opencode-mirror.yml"
PUBLICCODE = ROOT / "publiccode.yml"


def test_the_mirror_stays_inactive_until_configured_and_never_forces() -> None:
    text = WORKFLOW.read_text(encoding="utf-8")

    assert re.search(r"^permissions:\n  contents: read\n", text, re.M)
    assert 'if [[ -z "$MIRROR_URL" || -z "$MIRROR_TOKEN" ]]; then' in text
    assert text.count("if: steps.setup.outputs.configured == 'true'") == 2
    assert "--force" not in text and "--mirror" not in text and "'+refs" not in text
    assert "persist-credentials: false" in text
    assert 'echo "::add-mask::$auth"' in text
    push = text[text.index("git -c"):]
    assert re.findall(r"'([^']+)'", push) == [
        "refs/remotes/origin/main:refs/heads/main",
        "refs/remotes/origin/release/*:refs/heads/release/*",
        "refs/tags/v*:refs/tags/v*",
    ]


def test_the_mirror_reaches_the_token_only_through_env() -> None:
    text = WORKFLOW.read_text(encoding="utf-8")

    assert text.count("MIRROR_TOKEN: ${{ secrets.OPENCODE_MIRROR_TOKEN }}") == 2
    assert text.count("MIRROR_URL: ${{ vars.OPENCODE_MIRROR_URL }}") == 2
    assert "https://oauth2:" not in text


def test_publiccode_holds_the_fields_and_limits_of_the_standard() -> None:
    data = yaml.safe_load(PUBLICCODE.read_text(encoding="utf-8"))

    assert data["publiccodeYmlVersion"] == "0.4"
    assert data["url"].startswith("https://")
    assert data["legal"]["license"] in {"AGPL-3.0-only", "AGPL-3.0-or-later"}
    assert data["developmentStatus"] in {"concept", "development", "beta", "stable", "obsolete"}
    assert data["maintenance"]["type"] in {"internal", "contract", "community", "none"}
    assert data["maintenance"]["contacts"]
    for category in data["categories"]:
        assert re.fullmatch(r"[a-z]+(-[a-z]+)*", category), category
    for language in ("de", "en"):
        description = data["description"][language]
        assert len(description["genericName"]) <= 35
        assert len(description["shortDescription"]) <= 150
        assert 500 <= len(description["longDescription"]) <= 10000
        assert description["features"]
        assert all(len(feature) <= 100 for feature in description["features"])
