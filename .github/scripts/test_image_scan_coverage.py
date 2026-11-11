"""Every Dockerfile of the repository is scanned for CVEs by cve-scan.yml.

An image that publish-images.yml publishes is scanned as ghcr.io/criew/opaa-<name>:main; any other
Dockerfile is built by the scan itself (a target with "build": <context directory>). A new
Dockerfile that is neither stays outside the nightly scan without anyone noticing; this case fails
in the PR that adds it.
"""

import json
import re
import subprocess
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent.parent
PUBLISH = ROOT / ".github" / "workflows" / "publish-images.yml"
CVE_SCAN = ROOT / ".github" / "workflows" / "cve-scan.yml"


def dockerfiles() -> set[str]:
    listed = subprocess.run(["git", "ls-files"], cwd=ROOT, check=True, capture_output=True,
                            text=True)
    return {path for path in listed.stdout.split()
            if re.search(r"(^|/)(Dockerfile|Containerfile)(\.[^/]*)?$|\.Dockerfile$", path)}


def published() -> dict[str, str]:
    """Dockerfile -> image name of every image publish-images.yml builds."""
    workflow = yaml.safe_load(PUBLISH.read_text(encoding="utf-8"))
    images = {}
    for job in workflow["jobs"].values():
        for image in (job.get("strategy") or {}).get("matrix", {}).get("image", []) or []:
            if isinstance(image, dict) and "dockerfile" in image:
                images[image["dockerfile"]] = image["name"]
    return images


def scan_targets() -> list[dict]:
    """The fixed targets of the main state in the targets job of cve-scan.yml."""
    found = re.search(r"^\s*images='(\[.*\])'$", CVE_SCAN.read_text(encoding="utf-8"),
                      re.MULTILINE)
    assert found, "the images='[...]' list of the targets job is gone"
    return json.loads(found.group(1))


def test_every_dockerfile_is_scanned() -> None:
    targets = scan_targets()
    scanned_images = {target["image"] for target in targets}
    built = {target["build"] for target in targets if "build" in target}
    publish = published()

    files = dockerfiles()
    assert files, "no Dockerfile found - the check would pass without checking anything"
    unscanned = []
    for dockerfile in sorted(files):
        if dockerfile in publish:
            if f"ghcr.io/criew/opaa-{publish[dockerfile]}:main" not in scanned_images:
                unscanned.append(dockerfile)
        elif str(Path(dockerfile).parent) not in built:
            unscanned.append(dockerfile)
    assert not unscanned, (
        f"not scanned by cve-scan.yml: {unscanned}; publish the image or add a target with "
        '"build": <context directory> to the targets job')


def test_a_built_target_names_an_existing_dockerfile() -> None:
    for target in scan_targets():
        if "build" in target:
            assert (ROOT / target["build"] / "Dockerfile").is_file(), target
