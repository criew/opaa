"""Gradle plugin updates in renovate.json5 cover every coupled library.

Renovate runs ./gradlew for every Gradle update, so plugin code of a new version executes in the
container holding the token; plugins therefore wait longer and never merge automatically. A
library sharing a version.ref with a plugin is bumped together with it, and a library on the
buildscript classpath is loaded by Gradle itself. Both must carry the same rule, or a three-day
auto-merged library update moves the plugin past it.
"""

import re
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "backend" / "gradle" / "libs.versions.toml"
BUILD_FILES = [ROOT / "backend" / "build.gradle.kts", ROOT / "opaa-api" / "build.gradle.kts"]
RENOVATE_CONFIG = ROOT / "renovate.json5"


def config_blocks() -> list[str]:
    text = RENOVATE_CONFIG.read_text(encoding="utf-8")
    return re.findall(r"\{[^{}]*\}", text)


def block_with(*settings: str) -> str:
    blocks = [b for b in config_blocks() if all(s in b for s in settings)]
    assert len(blocks) == 1, f"expected exactly one renovate.json5 block with {settings}"
    return blocks[0]


def setting(block: str, key: str) -> str:
    match = re.search(rf"{key}:\s*([^,\n]+),", block)
    assert match, f"{key} missing in {block}"
    return match.group(1).strip()


def coupled_libraries() -> set[str]:
    catalog = tomllib.loads(CATALOG.read_text(encoding="utf-8"))
    plugin_refs = {
        p["version"]["ref"]
        for p in catalog["plugins"].values()
        if isinstance(p, dict) and isinstance(p.get("version"), dict)
    }
    libraries = catalog["libraries"]
    coupled = {
        lib["module"]
        for lib in libraries.values()
        if isinstance(lib, dict)
        and isinstance(lib.get("version"), dict)
        and lib["version"].get("ref") in plugin_refs
    }
    for build_file in BUILD_FILES:
        for alias in buildscript_aliases(build_file.read_text(encoding="utf-8")):
            coupled.add(libraries[alias]["module"])
    return coupled


def buildscript_aliases(build_script: str) -> set[str]:
    """Catalog library keys referenced anywhere before the plugins block, whatever the wrapper."""
    buildscript = build_script.split("plugins {", 1)[0]
    aliases = set()
    for reference in re.findall(r"\blibs\.([A-Za-z0-9.]+)", buildscript):
        reference = reference.removesuffix(".get")
        if not reference.startswith(("versions.", "plugins.", "bundles.")):
            aliases.add(reference.replace(".", "-"))
    return aliases


def test_coupled_libraries_exist() -> None:
    assert "org.springframework.boot:spring-boot-dependencies" in coupled_libraries()
    assert "tools.jackson:jackson-bom" in coupled_libraries()


def test_every_buildscript_reference_counts_whatever_the_wrapper() -> None:
    script = """buildscript {
    dependencies {
        classpath(platform(libs.a.bom))
        classpath(enforcedPlatform(libs.b.bom))
        classpath(libs.c)
        classpath(libs.d.get())
        classpath("x:y:${libs.versions.e.get()}")
    }
}

plugins {
    alias(libs.plugins.f)
}
"""
    assert buildscript_aliases(script) == {"a-bom", "b-bom", "c", "d"}


def test_every_coupled_library_gets_the_plugin_rule() -> None:
    plugin_rule = block_with("matchManagers: ['gradle']", "matchDepTypes: ['plugin']")
    library_rule = block_with("matchManagers: ['gradle']", "matchDepNames:")
    names = set(re.findall(r"'([\w.-]+:[\w.-]+)'", library_rule))
    missing = coupled_libraries() - names
    assert not missing, f"add to the gradle library rule in renovate.json5: {sorted(missing)}"
    for key in ("minimumReleaseAge", "automerge"):
        assert setting(library_rule, key) == setting(plugin_rule, key)
    assert setting(plugin_rule, "automerge") == "false"


def test_gradle_actions_never_merge_automatically() -> None:
    rule = block_with("matchManagers: ['github-actions']", "matchDepNames: ['gradle/actions']")
    assert setting(rule, "automerge") == "false"
