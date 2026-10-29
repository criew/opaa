"""Self-check for check_workflow_hardening.sh.

The CI run only exercises the green branch against the repository's own workflows; a change that
makes the guard laxer would go unnoticed there. These cases pin the rejecting branches of all three
rules (context in run:/script:, including toJSON(github.event), github['...'], github.ref_name and
vars.*; top-level permissions, checkout credentials), the end of a script
block, the mapping form of run: under defaults:, and the file selection. The same cases run once
more with mawk, the awk of the GitHub runners, when it is installed.
"""

import shutil
import subprocess
from pathlib import Path

import pytest
import yaml

WORKFLOWS = Path(__file__).resolve().parents[1] / "workflows"


@pytest.mark.parametrize("path", sorted(WORKFLOWS.glob("*.y*ml")), ids=lambda p: p.name)
def test_every_workflow_is_valid_yaml(path: Path) -> None:
    # GitHub runs no job of a workflow it cannot parse; the guard below reads workflows as text only.
    document = yaml.safe_load(path.read_text(encoding="utf-8"))

    assert isinstance(document, dict) and isinstance(document.get("jobs"), dict), path.name

SCRIPT = Path(__file__).resolve().parent / "check_workflow_hardening.sh"
BASH = shutil.which("bash") or "bash"
CHECKOUT = "      - uses: actions/checkout@0123456789abcdef0123456789abcdef01234567 # v7.0.1\n"
AWKS = [name for name in ("gawk", "mawk") if shutil.which(name)] or ["awk"]


@pytest.fixture(params=AWKS)
def awk_bin(request: pytest.FixtureRequest, tmp_path_factory: pytest.TempPathFactory) -> Path:
    """A directory that puts the chosen awk first on PATH under the name awk."""
    directory = tmp_path_factory.mktemp(f"awk-{request.param}")
    (directory / "awk").symlink_to(shutil.which(request.param) or request.param)
    return directory


def run_guard(repo: Path, awk_bin: Path) -> subprocess.CompletedProcess:
    env = {"PATH": f"{awk_bin}:/usr/bin:/bin"}
    return subprocess.run([BASH, SCRIPT.as_posix()], cwd=repo, capture_output=True, text=True, env=env)


def workflow(steps: str, permissions: str = "permissions:\n  contents: read\n") -> str:
    return (
        "name: Test\non:\n  pull_request:\n"
        + permissions
        + "jobs:\n  job:\n    runs-on: ubuntu-latest\n    steps:\n"
        + steps
    )


def checkout(persist: str = "false") -> str:
    return CHECKOUT + f"        with:\n          persist-credentials: {persist}\n"


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    subprocess.run(["git", "init", "-q"], cwd=tmp_path, check=True)
    write(tmp_path, "ci.yml", workflow(checkout() + "      - run: echo ok\n"))
    return tmp_path


def write(repo: Path, name: str, content: str) -> None:
    path = repo / ".github" / "workflows" / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def errors(result: subprocess.CompletedProcess) -> list[str]:
    return [line for line in result.stdout.splitlines() if line.startswith("::error file=")]


def test_accepts_context_through_env_and_trusted_expressions(repo: Path, awk_bin: Path) -> None:
    write(
        repo,
        "ci.yml",
        workflow(
            checkout()
            + "      - name: Env\n"
            "        env:\n"
            "          HEAD_REF: ${{ github.head_ref }}\n"
            "          BODY: ${{ github.event.pull_request.body }}\n"
            "        if: github.event_name == 'pull_request'\n"
            "        run: |\n"
            '          echo "$HEAD_REF" "${{ github.event_name }}" "${{ github.sha }}"\n'
            '          echo "${{ steps.x.outputs.inputs_count }}" "${{ matrix.shard }}"\n'
            '          echo "${{ github.event_path }}" "${{ github.repository }}" "${{ github.ref }}"\n'
            '          echo "${{ steps.x.outputs.vars_count }}" "${{ env.github }}"\n'
            "      - uses: actions/github-script@0123456789abcdef0123456789abcdef01234567 # v9.0.0\n"
            "        with:\n"
            "          script: |\n"
            "            const ref = process.env.HEAD_REF;\n"
            "      - with:\n"
            "          persist-credentials: true\n"
            "          ref: gh-pages\n"
            "        uses: actions/checkout@0123456789abcdef0123456789abcdef01234567 # v7.0.1\n"
            "      - run: echo done\n",
        ),
    )

    result = run_guard(repo, awk_bin)

    assert result.returncode == 0, result.stdout
    assert "All 1 workflows pass (3 scripts" in result.stdout


@pytest.mark.parametrize(
    "step",
    [
        '      - run: echo "${{ github.head_ref }}"\n',
        "      - run: |\n          echo start\n\n          git push origin ${{ github.head_ref }}\n",
        "      - name: Folded\n        run: >-\n          tool --ref ${{github.event.pull_request.title}}\n",
        "      - run: tool --date '${{ inputs.date }}'\n",
        "      - run: tool --body \"${{ github.event['pull_request'].body }}\"\n",
        '      - run: curl -H "Authorization: ${{ secrets.TOKEN }}" x\n',
        "      - run: echo ${{ GITHUB.HEAD_REF }}\n",
        "      - run: echo ${{ format('{0}', github.event.issue.title) }}\n",
        "      - uses: actions/github-script@0123456789abcdef0123456789abcdef01234567 # v9.0.0\n"
        "        with:\n          script: |\n            const t = '${{ github.event.pull_request.title }}';\n",
        "      - run: |\n          # ${{ github.head_ref }} is expanded inside a comment as well\n          true\n",
        "      - run: echo '${{ toJSON(github.event) }}' | jq .\n",
        "      - run: echo '${{ toJson(github.event.pull_request) }}'\n",
        "      - run: echo '${{ toJSON(github) }}'\n",
        "      - run: echo \"${{ github['head_ref'] }}\"\n",
        "      - run: git push origin \"${{ github.ref_name }}\"\n",
        "      - run: curl \"${{ vars.MIRROR_URL }}\"\n",
        "      - uses: actions/github-script@0123456789abcdef0123456789abcdef01234567 # v9.0.0\n"
        "        with:\n          script: |\n            const e = ${{ toJSON(github.event) }};\n",
    ],
)
def test_rejects_untrusted_context_in_a_script(repo: Path, awk_bin: Path, step: str) -> None:
    write(repo, "e2e.yml", workflow(checkout() + step))

    result = run_guard(repo, awk_bin)

    assert result.returncode == 1
    assert len(errors(result)) == 1, result.stdout
    assert errors(result)[0].startswith("::error file=.github/workflows/e2e.yml,line=")
    assert "interpolates github.head_ref" in result.stdout


def test_reports_the_line_of_the_offending_text(repo: Path, awk_bin: Path) -> None:
    write(repo, "e2e.yml", workflow(checkout() + "      - run: |\n          a\n          b ${{ inputs.x }}\n"))

    result = run_guard(repo, awk_bin)

    assert errors(result)[0].startswith("::error file=.github/workflows/e2e.yml,line=15::")


def test_a_script_block_ends_at_the_next_key(repo: Path, awk_bin: Path) -> None:
    write(
        repo,
        "e2e.yml",
        workflow(
            checkout()
            + "      - run: |\n"
            "          echo ok\n"
            "        env:\n"
            "          HEAD_REF: ${{ github.head_ref }}\n"
            "      - name: Next\n"
            "        with:\n"
            "          ref: ${{ github.event.pull_request.head.sha }}\n"
            "        run: echo ok\n"
        ),
    )

    result = run_guard(repo, awk_bin)

    assert result.returncode == 0, result.stdout


def test_a_run_mapping_under_defaults_is_not_a_script(repo: Path, awk_bin: Path) -> None:
    write(
        repo,
        "e2e.yml",
        "permissions: {}\njobs:\n  job:\n    defaults:\n      run:\n"
        "        working-directory: ${{ inputs.dir }}\n    steps:\n      - run: echo ok\n",
    )

    result = run_guard(repo, awk_bin)

    assert result.returncode == 0, result.stdout


def test_rejects_a_workflow_without_top_level_permissions(repo: Path, awk_bin: Path) -> None:
    job_only = "jobs:\n  job:\n    permissions:\n      contents: read\n    steps:\n      - run: echo ok\n"
    write(repo, "e2e.yml", "name: Test\non: push\n" + job_only)

    result = run_guard(repo, awk_bin)

    assert result.returncode == 1
    assert errors(result) == [
        "::error file=.github/workflows/e2e.yml,line=1::The workflow sets no top-level permissions: "
        "block. Grant the minimum (usually contents: read, or {}) and name anything more in the job "
        "that needs it."
    ]


@pytest.mark.parametrize(
    "steps",
    [
        CHECKOUT + "      - run: echo ok\n",
        CHECKOUT + "        with:\n          fetch-depth: 0\n      - run: echo ok\n",
        "      - name: Checkout\n" + CHECKOUT.replace("      - ", "        ")
        + "        with:\n          path: site\n",
        # persist-credentials of the next step does not count for this one.
        CHECKOUT + "      - uses: actions/checkout@0123 # v7.0.1\n"
        "        with:\n          persist-credentials: false\n",
    ],
)
def test_rejects_a_checkout_without_persist_credentials(repo: Path, awk_bin: Path, steps: str) -> None:
    write(repo, "e2e.yml", workflow(steps))

    result = run_guard(repo, awk_bin)

    assert result.returncode == 1
    assert len(errors(result)) == 1, result.stdout
    assert "actions/checkout without persist-credentials" in result.stdout


def test_checks_a_workflow_that_was_never_listed_anywhere(repo: Path, awk_bin: Path) -> None:
    write(repo, "new-workflow.yaml", workflow(checkout() + "      - run: echo ${{ inputs.x }}\n"))

    result = run_guard(repo, awk_bin)

    assert result.returncode == 1
    assert "::error file=.github/workflows/new-workflow.yaml,line=13::" in result.stdout
    assert "1 violations in 2 workflows" in result.stdout


def test_rejects_when_nothing_was_checked(repo: Path, awk_bin: Path) -> None:
    write(repo, "ci.yml", workflow(checkout()))

    result = run_guard(repo, awk_bin)

    assert result.returncode == 1
    assert "would pass without checking anything" in result.stdout


def test_rejects_a_repository_without_workflows(repo: Path, awk_bin: Path) -> None:
    shutil.rmtree(repo / ".github")

    result = run_guard(repo, awk_bin)

    assert result.returncode == 1
    assert "would pass without checking anything" in result.stdout
