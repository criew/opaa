#!/usr/bin/env python3
"""The flattened OCR image keeps the environment of its base image.

ocr/Dockerfile builds opaa-ocr FROM scratch with the file system of the docling-serve base image, so
the base image's configuration does not carry over; its Dockerfile restates the environment. This
check compares the two: every variable of the base image for the given platform must be set in the
built image with the same value, except the descriptive ones and those the image deliberately sets
otherwise. The base image is the one ocr/Dockerfile names.

  ocr_env_check.py <built image> <platform, e.g. linux/arm64>

Exit 1 on a missing or different variable, 2 on wrong usage.
"""

import json
import re
import subprocess
import sys
from pathlib import Path

DOCKERFILE = Path(__file__).resolve().parents[2] / "ocr" / "Dockerfile"
# Labels in all but name, read by no program in the image.
DESCRIPTIVE = {"SUMMARY", "DESCRIPTION", "NAME", "VERSION", "ARCH"}
# Set otherwise on purpose: HOME points to /tmp, the only writable path (ocr/Dockerfile).
OVERRIDDEN = {"HOME"}


def base_image() -> str:
    match = re.search(r"^FROM (\S+) AS build$", DOCKERFILE.read_text(encoding="utf-8"), re.M)
    if not match:
        raise SystemExit(f"no 'FROM <image> AS build' in {DOCKERFILE}")
    return match.group(1)


def run(*args: str) -> str:
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


def as_dict(env: list[str]) -> dict[str, str]:
    return dict(entry.split("=", 1) for entry in env)


def base_env(image: str, platform: str) -> dict[str, str]:
    raw = json.loads(run("docker", "buildx", "imagetools", "inspect", image, "--format", "{{json .Image}}"))
    config = raw if "config" in raw else raw[platform]
    return as_dict(config["config"].get("Env") or [])


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__.rsplit("\n\n", 2)[1], file=sys.stderr)
        return 2
    built, platform = argv
    expected = base_env(base_image(), platform)
    actual = as_dict(json.loads(run("docker", "image", "inspect", built, "--format", "{{json .Config.Env}}")))
    errors = []
    for name, value in sorted(expected.items()):
        if name in DESCRIPTIVE or name in OVERRIDDEN:
            continue
        if name not in actual:
            errors.append(f"{name} is missing; the base image sets {name}={value}")
        elif actual[name] != value:
            errors.append(f"{name}={actual[name]}, the base image sets {name}={value}")
    for error in errors:
        print(f"::error::{error}")
    if errors:
        return 1
    print(f"OK: {built} keeps the environment of its base image for {platform}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
