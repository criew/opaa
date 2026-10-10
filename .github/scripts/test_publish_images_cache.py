"""Release builds of publish-images.yml take nothing from the GitHub Actions cache.

Every job that runs on main may write to that cache, whatever scope its entries name, and a run on
a release tag restores from main's cache (docs/renovate.md). Only a release shows the difference
and no pull request runs one, so these checks keep the condition from being dropped unnoticed.
"""

import re
from pathlib import Path

WORKFLOW = Path(__file__).resolve().parents[1] / "workflows" / "publish-images.yml"
CONDITIONAL_GHA = re.compile(
    r"\$\{\{ github\.ref_type != 'tag' && format\('type=gha,[^']*scope=\{0\}-\{1\}', "
    r"matrix\.image\.name, matrix\.platform\.arch\) \|\| '' \}\}"
)


def cache_inputs() -> dict[str, str]:
    text = WORKFLOW.read_text(encoding="utf-8")
    return dict(re.findall(r"^\s*(cache-from|cache-to):\s*(.*?)\s*$", text, re.M))


def test_build_reads_and_writes_the_gha_cache_only_outside_release_tags() -> None:
    inputs = cache_inputs()

    assert set(inputs) == {"cache-from", "cache-to"}
    for key, value in inputs.items():
        assert CONDITIONAL_GHA.fullmatch(value), f"{key}: {value}"
    assert "mode=max" in inputs["cache-to"]


def test_no_unconditional_gha_cache_reference_remains() -> None:
    lines = [
        line
        for line in WORKFLOW.read_text(encoding="utf-8").splitlines()
        if "type=gha" in line and not line.lstrip().startswith("#")
    ]

    assert len(lines) == 2
    assert all("github.ref_type != 'tag'" in line for line in lines)
