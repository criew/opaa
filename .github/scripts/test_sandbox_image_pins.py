"""Self-check for the image pins of the sandbox (docker-compose.sandbox.yml, sandbox/).

The sandbox server holds the host's Docker socket, and foreign code runs in the sandbox image, so
the server image and the base image of sandbox/Dockerfile carry an exact version and the
registry's index digest. Renovate keeps both current with the rules for these files in
renovate.json5, and a regex manager reads the execd image in sandbox/config.toml, which no
regular manager sees. A drift between the files and the rules silently ends the updates; these
cases catch it in the PR that causes it.
"""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent.parent
COMPOSE = ROOT / "docker-compose.sandbox.yml"
DOCKERFILE = ROOT / "sandbox" / "Dockerfile"
SANDBOX_CONFIG = ROOT / "sandbox" / "config.toml"
RENOVATE_CONFIG = ROOT / "renovate.json5"
DIGEST = r"@sha256:[0-9a-f]{64}"
SANDBOX_FILES = "matchFileNames: ['docker-compose.sandbox.yml', 'sandbox/**']"


def config_blocks() -> list[str]:
    """Innermost {...} blocks of renovate.json5: customManagers and packageRules entries."""
    return re.findall(r"\{[^{}]*\}", RENOVATE_CONFIG.read_text(encoding="utf-8"))


def only_block(*settings: str) -> str:
    blocks = [block for block in config_blocks() if all(s in block for s in settings)]
    assert len(blocks) == 1, f"expected exactly one renovate.json5 block with {settings}: {blocks}"
    return blocks[0]


def js_string(block: str, key: str) -> str:
    """The first single-quoted string after key, with JSON5 escapes resolved."""
    literal = re.search(key + r":\s*\[?\s*'((?:[^'\\]|\\.)*)'", block)
    assert literal, f"{key} not found in {block}"
    return re.sub(r"\\(.)", r"\1", literal.group(1))


def test_the_server_image_carries_version_and_digest() -> None:
    images = re.findall(r"^\s+image:\s*(\S+)", COMPOSE.read_text(encoding="utf-8"), re.MULTILINE)
    server = [image for image in images if image.startswith("opensandbox/")]

    assert server, images
    for image in server:
        assert re.fullmatch(r"opensandbox/server:release-\d+\.\d+\.\d+" + DIGEST, image), image


def test_the_base_image_of_the_sandbox_carries_version_and_digest() -> None:
    lines = [line for line in DOCKERFILE.read_text(encoding="utf-8").splitlines()
             if line.startswith("FROM ")]

    assert len(lines) == 1, lines
    assert re.fullmatch(r"FROM python:\d+\.\d+\.\d+-slim-[a-z]+" + DIGEST, lines[0]), lines[0]


def test_the_python_packages_are_installed_with_hashes_only() -> None:
    dockerfile = DOCKERFILE.read_text(encoding="utf-8")
    installs = [line for line in dockerfile.replace("\\\n", " ").splitlines() if "pip install" in line]
    requirements = (ROOT / "sandbox" / "requirements.txt").read_text(encoding="utf-8")
    entries = [entry for entry in requirements.replace("\\\n", " ").splitlines()
               if entry.strip() and not entry.startswith("#")]

    assert len(installs) == 1, installs
    for option in ("--require-hashes", "--only-binary=:all:", "-r /tmp/requirements.txt"):
        assert option in installs[0], installs[0]
    assert entries
    for entry in entries:
        assert re.match(r"^[A-Za-z0-9._-]+==[^\s]+ ", entry), entry
        assert re.search(r"--hash=sha256:[0-9a-f]{64}", entry), entry


def test_renovate_pins_and_never_merges_the_sandbox_images_alone() -> None:
    rule = only_block(SANDBOX_FILES, "pinDigests: true")
    digest = only_block(SANDBOX_FILES, "matchUpdateTypes: ['digest']")

    assert "automerge: false" in rule
    assert "minimumReleaseAge: '3 days'" in rule
    # A new digest under the same version means a moved tag; it waits for approval.
    assert "dependencyDashboardApproval: true" in digest


def test_server_and_execd_move_together_on_release_tags_only() -> None:
    rule = only_block("matchDepNames: ['opensandbox/server', 'opensandbox/execd']")
    versioning = re.compile(js_string(rule, "versioning").removeprefix("regex:")
                            .replace("(?<", "(?P<"))

    assert "groupName: 'OpenSandbox'" in rule
    assert versioning.fullmatch("release-1.1.1")
    for tag in ("release-1.2.1-rc.1", "v0.2.3", "latest", "staging-c7dc78a-37604960612"):
        assert not versioning.fullmatch(tag), tag


def execd_manager() -> str:
    """The custom manager for sandbox/config.toml; its template holds braces of its own."""
    text = RENOVATE_CONFIG.read_text(encoding="utf-8")
    marker = "managerFilePatterns: ['sandbox/config.toml']"
    assert text.count(marker) == 1, f"expected exactly one renovate.json5 entry with {marker}"
    start = text.rindex("\n    {\n", 0, text.index(marker))
    return text[start:text.index("\n    },\n", start)]


def test_the_regex_manager_reads_the_execd_image() -> None:
    manager = execd_manager()
    assert "customType: 'regex'" in manager
    pattern = re.compile(js_string(manager, "matchStrings").replace("(?<", "(?P<"))
    template = js_string(manager, "autoReplaceStringTemplate")

    found = pattern.search(SANDBOX_CONFIG.read_text(encoding="utf-8"))

    assert "datasourceTemplate: 'docker'" in manager
    assert found, "the execd_image line of sandbox/config.toml no longer matches the manager"
    assert found.group("depName") == "opensandbox/execd"
    assert re.fullmatch(r"release-\d+\.\d+\.\d+", found.group("currentValue"))
    assert re.fullmatch(r"sha256:[0-9a-f]{64}", found.group("currentDigest") or ""), found.group(0)
    # The template writes the digest even where the line has none yet, so Renovate can pin it.
    assert "{{{newDigest}}}" in template and template.startswith('execd_image = "')
