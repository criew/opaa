"""Self-check for offline_bundle.sh and the wiring of the offline bundle into the release run.

The bundle is built only on a release tag or in the labelled trial (offline-bundle-probe.yml), so
these cases cover what runs without registry and signing: checksums, packing, the size limit of a
release asset and the signer the handbook pins.
"""

import os
import re
import shutil
import subprocess
import tarfile
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "offline_bundle.sh"
README = HERE / "offline_bundle_readme.md"
WORKFLOWS = HERE.parent / "workflows"
BASH = shutil.which("bash")

pytestmark = pytest.mark.skipif(BASH is None, reason="bash is required")


def run(*args: str, env: dict | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(
        [BASH, SCRIPT.as_posix(), *args],
        capture_output=True, text=True, env={**os.environ, **(env or {})},
    )


@pytest.fixture
def bundle(tmp_path: Path) -> Path:
    root = tmp_path / "opaa-offline-1.2.3"
    (root / "images").mkdir(parents=True)
    (root / "images" / "opaa-backend-1.2.3.oci.tar").write_bytes(os.urandom(8192))
    (root / "chart").mkdir()
    (root / "chart" / "opaa-1.2.3.tgz").write_bytes(b"chart")
    (root / "DIGESTS").write_text("ghcr.io/criew/opaa-backend:1.2.3@sha256:00\n", encoding="utf-8")
    (root / "SHA256SUMS.sigstore.json").write_text("{}", encoding="utf-8")
    return root


def listed_name(line: str) -> str:
    """The file name of a sha256sum line; the marker before it is " " or, in binary mode, "*"."""
    match = re.fullmatch(r"[0-9a-f]{64} [ *](.+)", line)
    assert match, line
    return match.group(1)


def sha256sum_check(directory: Path) -> int:
    return subprocess.run(
        [BASH, "-c", "sha256sum -c --quiet SHA256SUMS"], cwd=directory, capture_output=True,
    ).returncode


def test_sums_covers_every_file_but_itself_and_its_attestation(bundle: Path) -> None:
    assert run("sums", bundle.as_posix()).returncode == 0

    listed = [listed_name(line) for line in
              (bundle / "SHA256SUMS").read_text(encoding="utf-8").splitlines()]
    assert listed == ["DIGESTS", "chart/opaa-1.2.3.tgz", "images/opaa-backend-1.2.3.oci.tar"]
    assert sha256sum_check(bundle) == 0


def test_one_changed_byte_of_an_image_archive_fails_the_checksums(bundle: Path) -> None:
    run("sums", bundle.as_posix())
    archive = bundle / "images" / "opaa-backend-1.2.3.oci.tar"
    data = bytearray(archive.read_bytes())
    data[4096] ^= 0xFF
    archive.write_bytes(bytes(data))

    assert sha256sum_check(bundle) != 0


def test_pack_names_the_archive_after_the_directory(bundle: Path, tmp_path: Path) -> None:
    run("sums", bundle.as_posix())
    out = tmp_path / "out"

    result = run("pack", bundle.as_posix(), out.as_posix())

    assert result.returncode == 0, result.stderr
    archive = out / "opaa-offline-1.2.3.tar"
    assert result.stdout.strip().endswith("opaa-offline-1.2.3.tar")
    with tarfile.open(archive) as tar:
        names = tar.getnames()
    assert "opaa-offline-1.2.3/SHA256SUMS" in names
    assert "opaa-offline-1.2.3/SHA256SUMS.sigstore.json" in names
    assert all(name.startswith("opaa-offline-1.2.3") for name in names)
    sums = (out / "opaa-offline-1.2.3.tar.sha256").read_text(encoding="utf-8")
    assert listed_name(sums.strip()) == "opaa-offline-1.2.3.tar"


def test_pack_refuses_an_archive_above_the_limit(bundle: Path, tmp_path: Path) -> None:
    out = tmp_path / "out"

    result = run("pack", bundle.as_posix(), out.as_posix(), env={"OFFLINE_BUNDLE_MAX_BYTES": "1000"})

    assert result.returncode == 1
    assert "above the limit" in result.stderr
    assert not (out / "opaa-offline-1.2.3.tar").exists()


def test_the_default_limit_stays_below_two_gibibytes() -> None:
    limit = int(re.search(r"OFFLINE_BUNDLE_MAX_BYTES:-(\d+)", SCRIPT.read_text(encoding="utf-8")).group(1))

    assert limit < 2 * 1024**3


def job(workflow: str, name: str) -> str:
    text = (WORKFLOWS / workflow).read_text(encoding="utf-8")
    match = re.search(rf"^  {re.escape(name)}:\n(.*?)(?=^  [a-z][a-z-]*:\n|\Z)", text, re.M | re.S)
    assert match, f"job {name} missing in {workflow}"
    return match.group(1)


def test_the_release_carries_the_offline_bundle() -> None:
    bundle_job = job("publish-images.yml", "offline-bundle")
    assert "uses: ./.github/workflows/offline-bundle.yml" in bundle_job
    assert "if: startsWith(github.ref, 'refs/tags/v')" in bundle_job
    assert "update-package-from-run: true" in bundle_job
    assert re.search(r"needs: \[prepare, chart-attestation, update-package-attestation\]", bundle_job)

    release = job("publish-images.yml", "release")
    assert re.search(r"needs: \[.*\boffline-bundle\b.*\]", release)
    assert "name: offline-bundle" in release
    assert '"$RUNNER_TEMP"/offline-bundle/*' in release


def test_only_the_attest_job_holds_the_signing_token() -> None:
    collect = job("offline-bundle.yml", "collect")
    attest = job("offline-bundle.yml", "attest")

    assert "id-token" not in collect
    assert "id-token: write" in attest
    assert attest.count("uses: actions/attest@") == 2
    assert "offline_bundle.sh collect" not in attest


def test_the_instructions_pin_the_workflow_that_signs() -> None:
    readme = README.read_text(encoding="utf-8")

    assert "--signer-workflow @REPO@/.github/workflows/offline-bundle.yml" in readme
    assert "--source-ref refs/tags/v@VERSION@" in readme
    assert "--custom-trusted-root" in readme
    assert (WORKFLOWS / "offline-bundle.yml").is_file()


def test_the_release_notes_name_the_bundle_and_the_instructions(tmp_path: Path) -> None:
    notes = subprocess.run(
        [BASH, "deploy/helm/ci/release-notes.sh", "criew/opaa", "v1.2.4"],
        cwd=HERE.parents[1], capture_output=True, text=True, check=True,
        env={**os.environ, "UPGRADE_NOTES_DIR": tmp_path.as_posix()},
    ).stdout

    assert "`opaa-offline-1.2.4.tar`" in notes
    assert "images-und-laufzeit.md#installation-ohne-internetzugang" in notes
