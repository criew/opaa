#!/usr/bin/env python3
"""Checks dependency licenses against the allow list and writes a Markdown license overview.

Inputs are a CycloneDX SBOM (--cyclonedx, the backend's runtime SBOM) and/or the output of
`pnpm licenses list --prod --json` (--pnpm). A dependency passes if its license expression is
satisfied by .github/licenses/allowed.txt, or if .github/licenses/exceptions.txt lists its
versionless purl together with exactly the license it carries now. Several license entries of
one CycloneDX component count as alternatives (dual licensing); a missing license reads as
NOASSERTION. Procedure on a finding: docs/sbom.md, "Lizenzprüfung".

Exit codes: 0 all dependencies pass, 1 at least one does not, 2 malformed input or policy file.
Standard library only, so it runs on a bare runner without setup.
"""

from __future__ import annotations

import argparse
import fnmatch
import json
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from urllib.parse import unquote

NOASSERTION = "NOASSERTION"
STATUS_ALLOWED = "freigegeben"
STATUS_EXCEPTION = "Ausnahme"
STATUS_REJECTED = "nicht freigegeben"

# License exception ids of the SPDX exception list (https://spdx.org/licenses/exceptions-index.html).
# Only these are accepted after WITH; an id missing here fails the check, which errs on the safe side.
SPDX_EXCEPTIONS = frozenset(
    """
    389-exception Asterisk-exception Asterisk-linking-protocols-exception Autoconf-exception-2.0
    Autoconf-exception-3.0 Autoconf-exception-generic Autoconf-exception-generic-3.0
    Autoconf-exception-macro Bison-exception-1.24 Bison-exception-2.2 Bootloader-exception
    CGAL-linking-exception Classpath-exception-2.0 CLISP-exception-2.0 cryptsetup-OpenSSL-exception
    DigiRule-FOSS-exception eCos-exception-2.0 erlang-otp-linking-exception Fawkes-Runtime-exception
    FLTK-exception fmt-exception Font-exception-2.0 freertos-exception-2.0 GCC-exception-2.0
    GCC-exception-2.0-note GCC-exception-3.1 Gmsh-exception GNAT-exception GNOME-examples-exception
    GNU-compiler-exception gnu-javamail-exception GPL-3.0-389-ds-base-exception
    GPL-3.0-interface-exception GPL-3.0-linking-exception GPL-3.0-linking-source-exception GPL-CC-1.0
    GStreamer-exception-2005 GStreamer-exception-2008 harbour-exception i2p-gpl-java-exception
    Independent-modules-exception KiCad-libraries-exception LGPL-3.0-linking-exception
    libpri-OpenH323-exception Libtool-exception Linux-syscall-note LLGPL LLVM-exception
    LZMA-exception mif-exception mxml-exception OCaml-LGPL-linking-exception OCCT-exception-1.0
    OpenJDK-assembly-exception-1.0 openvpn-openssl-exception PCRE2-exception polyparse-exception
    PS-or-PDF-font-exception-20170817 QPL-1.0-INRIA-2004-exception Qt-GPL-exception-1.0
    Qt-LGPL-exception-1.1 Qwt-exception-1.0 romic-exception RRDtool-FLOSS-exception-2.0
    SANE-exception SHL-2.0 SHL-2.1 stunnel-exception SWI-exception Swift-exception
    Texinfo-exception u-boot-exception-2.0 UBDL-exception Universal-FOSS-exception-1.0
    vsftpd-openssl-exception WxWindows-exception-3.1 x11vnc-openssl-exception
    """.split()
)


class PolicyError(Exception):
    pass


@dataclass
class Dependency:
    purl: str  # versionless, percent-decoded: pkg:maven/<group>/<name>, pkg:npm/<name>
    display: str
    version: str
    license: str  # as shown in the overview and matched against exceptions
    alternatives: list[str]  # SPDX expressions or license names; one satisfied entry suffices
    status: str = STATUS_REJECTED
    exception: LicenseException | None = None


@dataclass
class LicenseException:
    pattern: str
    license: str
    reason: str
    line: int
    used: bool = field(default=False)


def versionless_purl(purl: str) -> str:
    base = re.split(r"[?#]", purl, maxsplit=1)[0]
    head, _, last = base.rpartition("/")
    if "@" in last:
        last = last.split("@", 1)[0]
    return unquote(f"{head}/{last}")


def load_allowed(path: Path) -> set[str]:
    allowed = set()
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if parse_expression(line) is None:
            raise PolicyError(f"{path}:{number}: not a valid SPDX expression: {line}")
        line = normalize_with(line)
        if " WITH " in line and line.partition(" WITH ")[2] not in SPDX_EXCEPTIONS:
            raise PolicyError(f"{path}:{number}: addition is not on the SPDX exception list: {line}")
        allowed.add(line)
    return allowed


def load_exceptions(path: Path) -> list[LicenseException]:
    exceptions = []
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = [part.strip() for part in line.split("|")]
        if len(parts) != 3 or not all(parts):
            raise PolicyError(f"{path.name}:{number}: expected '<purl pattern> | <license> | <reason>'")
        pattern, license_, reason = parts
        if not pattern.startswith("pkg:") or "@" in pattern.rpartition("/")[2]:
            raise PolicyError(f"{path.name}:{number}: pattern must be a versionless purl: {pattern}")
        exceptions.append(LicenseException(pattern, license_, reason, number))
    return exceptions


# --- SPDX expressions: OR binds weaker than AND, AND weaker than WITH. ---

TOKEN = re.compile(r"\s*(\(|\)|[A-Za-z0-9.+:-]+)")


def tokenize(text: str) -> list[str] | None:
    tokens, position = [], 0
    text = text.rstrip()
    while position < len(text):
        match = TOKEN.match(text, position)
        if not match:
            return None
        tokens.append(match.group(1))
        position = match.end()
    return tokens


def parse_expression(text: str):
    """Returns a tree of ("or"|"and", left, right) and ("id", license) nodes, or None."""
    tokens = tokenize(text)
    if not tokens:
        return None
    position = 0

    def peek() -> str | None:
        return tokens[position] if position < len(tokens) else None

    def take() -> str | None:
        nonlocal position
        token = peek()
        position += 1
        return token

    def operator(name: str) -> bool:
        token = peek()
        return token is not None and token.upper() == name

    def parse_or():
        left = parse_and()
        while left is not None and operator("OR"):
            take()
            right = parse_and()
            left = None if right is None else ("or", left, right)
        return left

    def parse_and():
        left = parse_atom()
        while left is not None and operator("AND"):
            take()
            right = parse_atom()
            left = None if right is None else ("and", left, right)
        return left

    def parse_atom():
        token = take()
        if token is None or token == ")" or token.upper() in ("AND", "OR", "WITH"):
            return None
        if token == "(":
            inner = parse_or()
            return inner if take() == ")" else None
        if operator("WITH"):
            take()
            addition = take()
            if addition is None or addition in ("(", ")"):
                return None
            return ("id", f"{token} WITH {addition}")
        return ("id", token)

    tree = parse_or()
    return tree if tree is not None and position == len(tokens) else None


def normalize_with(expression: str) -> str:
    return re.sub(r"\s+with\s+", " WITH ", expression.strip(), flags=re.IGNORECASE)


def satisfied(tree, allowed: set[str]) -> bool:
    kind = tree[0]
    if kind == "or":
        return satisfied(tree[1], allowed) or satisfied(tree[2], allowed)
    if kind == "and":
        return satisfied(tree[1], allowed) and satisfied(tree[2], allowed)
    license_ = tree[1]
    base, _, addition = license_.partition(" WITH ")
    # A listed SPDX exception only adds permissions, so an allowed base license suffices; any
    # other addition (AdditionRef-*, Commons-Clause) may restrict and is never allowed.
    if addition and addition not in SPDX_EXCEPTIONS:
        return False
    return license_ in allowed or base in allowed


def alternative_allowed(alternative: str, allowed: set[str]) -> bool:
    tree = parse_expression(alternative)
    return tree is not None and satisfied(tree, allowed)


# --- inputs ---


def read_cyclonedx(path: Path, ignore: list[str]) -> list[Dependency]:
    data = json.loads(path.read_text(encoding="utf-8"))
    dependencies = []
    for component in data.get("components", []):
        purl = component.get("purl")
        if not purl:
            raise PolicyError(f"{path}: component without purl: {component.get('name')}")
        bare = versionless_purl(purl)
        if any(fnmatch.fnmatchcase(bare, pattern) for pattern in ignore):
            continue
        alternatives, shown = [], []
        for entry in component.get("licenses", []):
            if "expression" in entry:
                alternatives.append(entry["expression"])
                shown.append(entry["expression"])
            elif "license" in entry:
                license_ = entry["license"]
                value = license_.get("id") or license_.get("name")
                if value:
                    # A bare name is kept apart: it is never an SPDX expression, even if it parses.
                    alternatives.append(value if "id" in license_ else f"\0{value}")
                    shown.append(value)
        if bare.startswith("pkg:maven/"):
            display = bare[len("pkg:maven/") :].replace("/", ":")
        else:
            display = bare.split("/", 1)[1]
        dependencies.append(
            Dependency(
                purl=bare,
                display=display,
                version=component.get("version", ""),
                license=" OR ".join(shown) if shown else NOASSERTION,
                alternatives=alternatives,
            )
        )
    return dependencies


def read_pnpm(path: Path) -> list[Dependency]:
    data = json.loads(path.read_text(encoding="utf-8"))
    dependencies = []
    for packages in data.values():
        for package in packages:
            license_ = (package.get("license") or "").strip()
            if not license_ or license_.lower() == "unknown":
                license_ = NOASSERTION
            for version in package["versions"]:
                dependencies.append(
                    Dependency(
                        purl=f"pkg:npm/{package['name']}",
                        display=package["name"],
                        version=version,
                        license=license_,
                        alternatives=[] if license_ == NOASSERTION else [license_],
                    )
                )
    return dependencies


# --- evaluation and report ---


def evaluate(dependencies: list[Dependency], allowed: set[str], exceptions: list[LicenseException]) -> None:
    for dependency in dependencies:
        if any(alternative_allowed(alternative, allowed) for alternative in dependency.alternatives):
            dependency.status = STATUS_ALLOWED
            continue
        for exception in exceptions:
            if fnmatch.fnmatchcase(dependency.purl, exception.pattern):
                dependency.exception = exception
                if exception.license == dependency.license:
                    exception.used = True
                    dependency.status = STATUS_EXCEPTION
                    break


def escape(text: str) -> str:
    return text.replace("|", "\\|").replace("\0", "")


def write_report(path: Path, title: str, dependencies: list[Dependency], exceptions: list[LicenseException]) -> None:
    ordered = sorted(dependencies, key=lambda d: (d.display, d.version))
    counts: dict[str, int] = {}
    for dependency in ordered:
        counts[dependency.license] = counts.get(dependency.license, 0) + 1
    lines = [
        f"# Lizenzübersicht {title}".rstrip(),
        "",
        "Generiert von `.github/scripts/check_licenses.py` gegen die Freigabeliste "
        "`.github/licenses/allowed.txt` und die Ausnahmeliste `.github/licenses/exceptions.txt`. "
        "Nicht von Hand bearbeiten; Verfahren in `docs/sbom.md`, Abschnitt „Lizenzprüfung“.",
        "",
        f"- Abhängigkeiten: {len(ordered)}",
        f"- freigegeben: {sum(d.status == STATUS_ALLOWED for d in ordered)}",
        f"- Ausnahme: {sum(d.status == STATUS_EXCEPTION for d in ordered)}",
        f"- nicht freigegeben: {sum(d.status == STATUS_REJECTED for d in ordered)}",
        "",
        "## Lizenzen",
        "",
        "| Lizenz | Anzahl |",
        "|---|---|",
    ]
    for license_, count in sorted(counts.items(), key=lambda item: (-item[1], item[0])):
        lines.append(f"| {escape(license_)} | {count} |")
    lines += ["", "## Abhängigkeiten", "", "| Paket | Version | Lizenz | Status |", "|---|---|---|---|"]
    for dependency in ordered:
        lines.append(
            f"| {escape(dependency.display)} | {escape(dependency.version)} | "
            f"{escape(dependency.license)} | {dependency.status} |"
        )
    used = [exception for exception in exceptions if exception.used]
    if used:
        lines += ["", "## Angewandte Ausnahmen", "", "| Paket (purl) | Lizenz | Begründung |", "|---|---|---|"]
        for exception in used:
            lines.append(f"| `{exception.pattern}` | {escape(exception.license)} | {escape(exception.reason)} |")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--allowed", type=Path, required=True)
    parser.add_argument("--exceptions", type=Path, required=True)
    parser.add_argument("--cyclonedx", type=Path, action="append", default=[])
    parser.add_argument("--pnpm", type=Path, action="append", default=[])
    parser.add_argument("--ignore", action="append", default=[], help="versionless purl pattern of own modules")
    parser.add_argument("--title", default="")
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args(argv)

    try:
        allowed = load_allowed(args.allowed)
        exceptions = load_exceptions(args.exceptions)
        dependencies = []
        for path in args.cyclonedx:
            dependencies += read_cyclonedx(path, args.ignore)
        for path in args.pnpm:
            dependencies += read_pnpm(path)
    except (PolicyError, OSError, ValueError, KeyError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    evaluate(dependencies, allowed, exceptions)
    write_report(args.report, args.title, dependencies, exceptions)

    rejected = [d for d in dependencies if d.status == STATUS_REJECTED]
    for dependency in sorted(rejected, key=lambda d: (d.display, d.version)):
        hint = ""
        if dependency.exception is not None:
            hint = (
                f" (exception {args.exceptions.name}:{dependency.exception.line} records "
                f"'{dependency.exception.license}', license changed)"
            )
        print(f"NOT ALLOWED  {dependency.display} {dependency.version}: {dependency.license}{hint}")

    # Only exceptions of the ecosystems checked in this run can be judged unused.
    schemes = {dependency.purl.split("/", 1)[0] for dependency in dependencies}
    for exception in exceptions:
        if not exception.used and exception.pattern.split("/", 1)[0] in schemes:
            print(f"warning: unused exception {args.exceptions.name}:{exception.line}: {exception.pattern}")

    print(
        f"{len(dependencies)} dependencies checked, {len(rejected)} not allowed; report: {args.report}"
    )
    return 1 if rejected else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
