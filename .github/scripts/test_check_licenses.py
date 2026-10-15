"""Self-check for check_licenses.py.

The CI run only exercises the repository's current dependencies, which all pass; a change that
makes the check laxer would go unnoticed there. These cases pin the rejecting branches (license
not on the allow list, missing license, license name instead of SPDX id, unparsable expression,
exception whose recorded license no longer matches) and the policy files in .github/licenses/.
"""

import json
import subprocess
import sys
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).resolve().parent
SCRIPT = SCRIPTS / "check_licenses.py"
POLICY = SCRIPTS.parent / "licenses"
OWN = "pkg:maven/io.opaa/backend@0.0.0-dev?project_path=%3A"

ALLOWED = """\
# comment
Apache-2.0
MIT
LGPL-2.1-only
GPL-2.0-only WITH Classpath-exception-2.0
"""


def license_entry(value: str) -> dict:
    if value.startswith("expr:"):
        return {"expression": value[len("expr:") :]}
    if value.startswith("name:"):
        return {"license": {"name": value[len("name:") :]}}
    return {"license": {"id": value}}


def component(purl: str, *licenses: str) -> dict:
    name_version = purl.split("?")[0].rsplit("/", 1)[1]
    name, version = name_version.split("@")
    result = {"type": "library", "name": name, "version": version, "purl": purl, "bom-ref": purl}
    if licenses:
        result["licenses"] = [license_entry(value) for value in licenses]
    return result


def cyclonedx(*components: dict) -> str:
    own = {"type": "library", "name": "backend", "version": "0.0.0-dev", "purl": OWN}
    return json.dumps(
        {
            "bomFormat": "CycloneDX",
            "metadata": {"component": {"name": "opaa-backend", "bom-ref": "root"}},
            "components": [own, *components],
        }
    )


@pytest.fixture
def work(tmp_path: Path) -> Path:
    (tmp_path / "allowed.txt").write_text(ALLOWED, encoding="utf-8")
    (tmp_path / "exceptions.txt").write_text("# none\n", encoding="utf-8")
    return tmp_path


def run_check(work: Path, *, sbom: str | None = None, pnpm: dict | None = None) -> subprocess.CompletedProcess:
    args = [
        sys.executable,
        "-I",
        SCRIPT.as_posix(),
        "--allowed",
        (work / "allowed.txt").as_posix(),
        "--exceptions",
        (work / "exceptions.txt").as_posix(),
        "--title",
        "Test",
        "--report",
        (work / "report.md").as_posix(),
    ]
    if sbom is not None:
        (work / "bom.json").write_text(sbom, encoding="utf-8")
        args += ["--cyclonedx", (work / "bom.json").as_posix(), "--ignore", "pkg:maven/io.opaa/*"]
    if pnpm is not None:
        (work / "pnpm.json").write_text(json.dumps(pnpm), encoding="utf-8")
        args += ["--pnpm", (work / "pnpm.json").as_posix()]
    return subprocess.run(args, capture_output=True, text=True)


def report(work: Path) -> str:
    return (work / "report.md").read_text(encoding="utf-8")


def test_accepts_allowed_licenses_and_writes_report(work: Path) -> None:
    sbom = cyclonedx(
        component("pkg:maven/org.example/alpha@1.0?type=jar", "Apache-2.0"),
        component("pkg:maven/org.example/beta@2.0?type=jar", "MIT"),
    )

    result = run_check(work, sbom=sbom)

    assert result.returncode == 0, result.stdout + result.stderr
    text = report(work)
    assert "| org.example:alpha | 1.0 | Apache-2.0 | freigegeben |" in text
    assert "| org.example:beta | 2.0 | MIT | freigegeben |" in text
    # The project's own modules are no dependencies.
    assert "io.opaa" not in text


@pytest.mark.parametrize(
    "licenses",
    [
        ("GPL-2.0-only",),
        ("expr:GPL-2.0-only OR SSPL-1.0",),
        ("expr:MIT AND GPL-2.0-only",),
        ("expr:MIT OR (",),
        ("name:Some Custom License",),
        (),
    ],
    ids=["not-allowed", "or-without-allowed", "and-with-disallowed", "unparsable", "name-only", "missing"],
)
def test_rejects_license_that_is_not_allowed(work: Path, licenses: tuple[str, ...]) -> None:
    sbom = cyclonedx(
        component("pkg:maven/org.example/fine@1.0?type=jar", "MIT"),
        component("pkg:maven/org.example/bad@3.1?type=jar", *licenses),
    )

    result = run_check(work, sbom=sbom)

    assert result.returncode == 1
    assert "org.example:bad 3.1" in result.stdout
    assert "org.example:fine" not in result.stdout
    assert "| org.example:bad | 3.1 |" in report(work)
    assert "nicht freigegeben" in report(work)


def test_missing_license_is_reported_as_noassertion(work: Path) -> None:
    result = run_check(work, sbom=cyclonedx(component("pkg:maven/org.example/bare@1.0?type=jar")))

    assert result.returncode == 1
    assert "NOASSERTION" in result.stdout


@pytest.mark.parametrize(
    "licenses",
    [
        ("expr:MIT OR GPL-2.0-only",),
        ("expr:(MIT AND Apache-2.0) OR SSPL-1.0",),
        ("expr:GPL-2.0-only WITH Classpath-exception-2.0",),
        ("expr:Apache-2.0 WITH LLVM-exception",),
        # Several license entries of one component are alternatives (dual licensing).
        ("EPL-2.0", "LGPL-2.1-only"),
        ("name:CDDL, v1.0", "MIT"),
    ],
    ids=["or", "nested", "allowed-with", "with-on-allowed-base", "dual-entries", "name-plus-allowed"],
)
def test_accepts_expression_with_an_allowed_alternative(work: Path, licenses: tuple[str, ...]) -> None:
    sbom = cyclonedx(component("pkg:maven/org.example/dual@1.0?type=jar", *licenses))

    result = run_check(work, sbom=sbom)

    assert result.returncode == 0, result.stdout + result.stderr


def test_exception_accepts_package_with_recorded_license(work: Path) -> None:
    (work / "exceptions.txt").write_text(
        "pkg:maven/org.example/special | FSL-1.1-ALv2 | Begründung A\n"
        "pkg:maven/org.family/* | Family Licence | Begründung B\n",
        encoding="utf-8",
    )
    sbom = cyclonedx(
        component("pkg:maven/org.example/special@5.0?type=jar", "FSL-1.1-ALv2"),
        component("pkg:maven/org.family/one@1.0?type=jar", "name:Family Licence"),
        component("pkg:maven/org.family/two@1.0?type=jar", "name:Family Licence"),
    )

    result = run_check(work, sbom=sbom)

    assert result.returncode == 0, result.stdout + result.stderr
    text = report(work)
    assert "| org.example:special | 5.0 | FSL-1.1-ALv2 | Ausnahme |" in text
    assert "| org.family:two | 1.0 | Family Licence | Ausnahme |" in text
    assert "Begründung A" in text


def test_exception_does_not_cover_a_changed_license(work: Path) -> None:
    (work / "exceptions.txt").write_text(
        "pkg:maven/org.example/special | FSL-1.1-ALv2 | Begründung\n", encoding="utf-8"
    )
    sbom = cyclonedx(component("pkg:maven/org.example/special@6.0?type=jar", "BUSL-1.1"))

    result = run_check(work, sbom=sbom)

    assert result.returncode == 1
    assert "org.example:special 6.0" in result.stdout
    assert "FSL-1.1-ALv2" in result.stdout


def test_unused_exception_is_only_a_warning_and_scoped_to_its_ecosystem(work: Path) -> None:
    (work / "exceptions.txt").write_text(
        "pkg:maven/org.gone/old | UnRAR | Begründung\n"
        "pkg:npm/@fontsource/* | OFL-1.1 | Begründung\n",
        encoding="utf-8",
    )
    sbom = cyclonedx(component("pkg:maven/org.example/alpha@1.0?type=jar", "MIT"))

    result = run_check(work, sbom=sbom)

    assert result.returncode == 0, result.stdout + result.stderr
    assert "pkg:maven/org.gone/old" in result.stdout
    assert "@fontsource" not in result.stdout


@pytest.mark.parametrize(
    "line",
    [
        "pkg:maven/org.example/x | MIT\n",
        "pkg:maven/org.example/x | MIT | \n",
        "org.example:x | MIT | Begründung\n",
        "pkg:maven/org.example/x@1.0 | MIT | Begründung\n",
    ],
    ids=["no-reason", "empty-reason", "no-purl", "with-version"],
)
def test_rejects_malformed_exception_line(work: Path, line: str) -> None:
    (work / "exceptions.txt").write_text(line, encoding="utf-8")
    sbom = cyclonedx(component("pkg:maven/org.example/x@1.0?type=jar", "MIT"))

    result = run_check(work, sbom=sbom)

    assert result.returncode == 2
    assert "exceptions.txt:1" in result.stderr


def test_reads_pnpm_license_listing(work: Path) -> None:
    (work / "exceptions.txt").write_text(
        "pkg:npm/@fontsource/* | OFL-1.1 | Schriftdateien\n", encoding="utf-8"
    )
    pnpm = {
        "MIT": [{"name": "@scope/lib", "versions": ["1.0.0", "1.1.0"], "license": "MIT"}],
        "OFL-1.1": [{"name": "@fontsource/inter", "versions": ["5.3.0"], "license": "OFL-1.1"}],
        "(MIT OR GPL-3.0-or-later)": [
            {"name": "dual", "versions": ["2.0.0"], "license": "(MIT OR GPL-3.0-or-later)"}
        ],
        "Unknown": [{"name": "mystery", "versions": ["0.1.0"], "license": "Unknown"}],
    }

    result = run_check(work, pnpm=pnpm)

    assert result.returncode == 1
    assert "mystery 0.1.0" in result.stdout
    assert "NOASSERTION" in result.stdout
    text = report(work)
    assert "| @scope/lib | 1.1.0 | MIT | freigegeben |" in text
    assert "| @fontsource/inter | 5.3.0 | OFL-1.1 | Ausnahme |" in text
    assert "| dual | 2.0.0 | (MIT OR GPL-3.0-or-later) | freigegeben |" in text


def test_repository_policy_files_are_well_formed(tmp_path: Path) -> None:
    # An empty inventory loads both policy files: a malformed line exits with 2.
    (tmp_path / "pnpm.json").write_text("{}", encoding="utf-8")
    result = subprocess.run(
        [
            sys.executable,
            "-I",
            SCRIPT.as_posix(),
            "--allowed",
            (POLICY / "allowed.txt").as_posix(),
            "--exceptions",
            (POLICY / "exceptions.txt").as_posix(),
            "--pnpm",
            (tmp_path / "pnpm.json").as_posix(),
            "--report",
            (tmp_path / "report.md").as_posix(),
        ],
        capture_output=True,
        text=True,
    )

    assert result.returncode == 0, result.stdout + result.stderr


def test_allow_list_stays_free_of_licenses_incompatible_with_agpl() -> None:
    entries = {
        line.strip()
        for line in (POLICY / "allowed.txt").read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }
    # Strong copyleft or non-free licenses that only an exception with a reason may admit.
    for forbidden in ("GPL-2.0-only", "EPL-1.0", "EPL-2.0", "CDDL-1.0", "MPL-1.1", "SSPL-1.0", "BUSL-1.1"):
        assert forbidden not in entries
