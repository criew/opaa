"""Updates of gradle/actions never merge automatically.

The dependency graph job trusts exactly one version of the plugin that gradle/actions injects
(backend/gradle/verification-metadata.xml). A bump moving that version fails only the resolve job,
which is no required check, and afterwards the submit on main.
"""

import re
from pathlib import Path

RENOVATE_CONFIG = Path(__file__).resolve().parents[2] / "renovate.json5"


def test_gradle_actions_never_merge_automatically() -> None:
    text = RENOVATE_CONFIG.read_text(encoding="utf-8")
    blocks = [
        b
        for b in re.findall(r"\{[^{}]*\}", text)
        if "matchManagers: ['github-actions']" in b and "matchDepNames: ['gradle/actions']" in b
    ]
    assert len(blocks) == 1, "expected exactly one renovate.json5 rule for gradle/actions"
    assert re.search(r"automerge:\s*false,", blocks[0])
