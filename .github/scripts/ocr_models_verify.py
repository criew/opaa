#!/usr/bin/env python3
"""Checks ocr/models.sha256 against sources independent of the build's own download.

models.sha256 pins the models baked into opaa-ocr; its lines come from a build. This check ties
every line to a second source, so a file tampered with on its first download cannot become the pin:

  docling-project--<repo>/<path>  the Hugging Face API at the commit ocr/models.revisions pins for
                                  the repository: the LFS SHA256 of a large file, the content of
                                  any other file at that commit
  RapidOcr/<file>                 the SHA256 in default_models.yaml of the rapidocr package in the
                                  image; a file listed there without one (the dictionary) is fetched
                                  from its URL there and must match - the source the build used, so
                                  this shows only that it still serves the same file (docs/sbom.md)

The package comes from the base image, pinned by digest, which installs it from its lock file.

  ocr_models_verify.py <image> <models.sha256> <models.revisions>

Exit 1 if a line has no source or differs from it, 2 on wrong usage.
"""

import hashlib
import json
import subprocess
import sys
import urllib.parse
import urllib.request
from pathlib import Path

HF = "https://huggingface.co"
HF_PREFIX = "docling-project--"

# Runs inside the image without network: the hashes rapidocr declares and the Hugging Face commits
# of the downloads, as JSON on stdout.
IN_IMAGE = r"""
import json, os, pathlib
import yaml, rapidocr
root = pathlib.Path(os.environ["DOCLING_SERVE_ARTIFACTS_PATH"])
rapid = {}
def walk(node):
    if isinstance(node, dict):
        for key in ("model_dir", "dict_url"):
            url = node.get(key)
            name = url.rsplit("/", 1)[-1] if isinstance(url, str) else ""
            if url and url.startswith("https://") and "." in name:
                sha = node.get("SHA256") if key == "model_dir" else None
                entry = [url, sha]
                if entry not in rapid.setdefault(name, []):
                    rapid[name].append(entry)
        for value in node.values():
            walk(value)
    elif isinstance(node, list):
        for value in node:
            walk(value)
walk(yaml.safe_load((pathlib.Path(rapidocr.__file__).parent / "default_models.yaml").read_text()))
commits = {}
for meta in root.glob("*/.cache/huggingface/download/**/*.metadata"):
    commits.setdefault(meta.parts[len(root.parts)], set()).add(meta.read_text().split("\n", 1)[0])
print(json.dumps({"rapidocr": rapid, "commits": {k: sorted(v) for k, v in commits.items()}}))
"""


def fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "opaa-ocr-models-verify"})
    with urllib.request.urlopen(request, timeout=120) as response:  # noqa: S310 (https only)
        return response.read()


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def read_pins(path: Path) -> list[tuple[str, str]]:
    pins = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        if raw.strip():
            digest, name = raw.split(maxsplit=1)
            pins.append((name, digest))
    return pins


def read_revisions(path: Path) -> dict[str, str]:
    revisions = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split("#", 1)[0].strip()
        if line:
            repo, commit = line.split()
            revisions[repo] = commit
    return revisions


def in_image(image: str) -> dict:
    result = subprocess.run(
        ["docker", "run", "--rm", "--network", "none", "--entrypoint", "/opt/app-root/bin/python",
         image, "-c", IN_IMAGE],
        check=True, capture_output=True, text=True,
    )
    return json.loads(result.stdout)


def check_hf(repo: str, commit: str, files: dict[str, str]) -> list[str]:
    tree_url = f"{HF}/api/models/{repo}/tree/{commit}?recursive=true"
    tree = {entry["path"]: entry for entry in json.loads(fetch(tree_url)) if entry["type"] == "file"}
    errors = []
    for path, digest in sorted(files.items()):
        entry = tree.get(path)
        if entry is None:
            errors.append(f"{repo}@{commit}: {path} does not exist")
            continue
        if entry.get("lfs"):
            source, expected = "LFS", entry["lfs"]["oid"]
        else:
            quoted = urllib.parse.quote(path)
            source, expected = "file", sha256(fetch(f"{HF}/{repo}/resolve/{commit}/{quoted}"))
        state = "ok" if expected == digest else "DIFFERS"
        print(f"{state}: {HF_PREFIX}{repo.split('/', 1)[1]}/{path} ({source} at {commit[:12]})")
        if expected != digest:
            errors.append(f"{repo}@{commit}: {path} has {expected}, models.sha256 {digest}")
    return errors


def check_rapidocr(name: str, digest: str, candidates: list) -> str | None:
    if not candidates:
        return f"RapidOcr/{name}: not in default_models.yaml"
    declared = {sha for _, sha in candidates if sha}
    if declared:
        print(f"{'ok' if digest in declared else 'DIFFERS'}: RapidOcr/{name} (default_models.yaml)")
        return None if digest in declared else f"RapidOcr/{name}: declared {sorted(declared)}"
    for url, _ in candidates:
        if sha256(fetch(url)) == digest:
            print(f"ok: RapidOcr/{name} ({url})")
            return None
    return f"RapidOcr/{name}: matches none of {[url for url, _ in candidates]}"


def main(argv: list[str]) -> int:
    if len(argv) != 3:
        print(__doc__.rsplit("\n\n", 2)[1], file=sys.stderr)
        return 2
    image, pins_path, revisions_path = argv
    pins = read_pins(Path(pins_path))
    revisions = read_revisions(Path(revisions_path))
    found = in_image(image)
    for folder, commits in sorted(found["commits"].items()):
        print(f"downloaded from commit {', '.join(commits)}: {folder}")

    errors = []
    hf_files: dict[str, dict[str, str]] = {}
    for name, digest in pins:
        folder, _, rest = name.partition("/")
        if folder.startswith(HF_PREFIX):
            hf_files.setdefault("docling-project/" + folder[len(HF_PREFIX):], {})[rest] = digest
        elif folder == "RapidOcr":
            error = check_rapidocr(rest, digest, found["rapidocr"].get(rest, []))
            if error:
                errors.append(error)
        else:
            errors.append(f"{name}: no independent source known")
    for repo, files in sorted(hf_files.items()):
        if repo not in revisions:
            errors.append(f"{repo}: no commit in models.revisions")
            continue
        errors.extend(check_hf(repo, revisions[repo], files))
    for repo in sorted(set(revisions) - set(hf_files)):
        errors.append(f"{repo}: pinned in models.revisions, but no file of it is baked in")

    for error in errors:
        print(f"::error::{error}")
    if not errors:
        print(f"OK: all {len(pins)} model files match a source independent of the build")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
