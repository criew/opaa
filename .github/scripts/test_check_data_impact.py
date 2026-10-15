"""Self-check for check_data_impact.sh: a matched path without label and without "Keine Wirkung"
fails, the label or a ticked "Keine Wirkung" passes, a ticked impact without label fails, unmatched
paths pass. For the real path list: every pattern matches a tracked file, every backend file with an
outgoing client, a model call, a system prompt or a retention period matches a pattern, and the
Renovate exception of data-impact.yml names the account of gitAuthor in renovate.json5. For the real
PR template: every impact box, the AI classification included, needs the label."""

import json
import os
import re
import shutil
import subprocess
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).resolve().parent
SCRIPT = SCRIPTS / "check_data_impact.sh"
REPO_ROOT = SCRIPTS.parent.parent
PATHS = REPO_ROOT / ".github" / "data-impact-paths.txt"
BASH = shutil.which("bash") or "bash"

# Code that sends data out of OPAA: HTTP, mail and S3 clients, model calls and the project's own
# wrappers around them. A new wrapper belongs here as well.
FLOW = re.compile(
    r"\b(RestClient|RestTemplate|WebClient|HttpClient|HttpURLConnection|URLConnection|Socket"
    r"|ChatClient|ChatModel|StreamingChatModel|EmbeddingModel|ImageModel|TranscriptionModel"
    r"|JavaMailSender|S3Client|S3AsyncClient|S3Presigner|McpSyncClient|McpAsyncClient"
    r"|SourceHttpClientFactory|ActiveChatModelResolver|BoundedDownloader|RedirectFollowingFetcher"
    r"|SourceFormPost|RemoteOriginalAccess|RerankClient|TranscriptionClient"
    r"|MailService|MailSenderProvider|GraphClient|GraphConnections|S3SdkClient|S3ObjectStore"
    r"|SmbShareClient|KeycloakAdminApi|ConfluenceClient|DriveApi|DropboxApi|NextcloudDav"
    r"|FeedFetcher|OAuthClient|OidcDiscoveryClient|McpServerDiscovery)\b"
)
# Text given to a model as instruction.
PROMPT = re.compile(
    r"\b[A-Z_]*PROMPT[A-Z_]*\s*=|\.system\(\s*[^)\s]|\bSystemMessage\b|\bSystemPromptTemplate\b"
)
# A default period after which data is deleted or archived.
RETENTION = re.compile(
    r"static\s+final\s+(Duration|Period)\s+\w*(RETENTION|DELET|KEEP|PURGE|CLEANUP|ARCHIV)\w*\s*="
)
RETENTION_NAME = re.compile(r"Retention|Cleanup|Purge")
COMMENT_LINE = re.compile(r"\s*(//|/?\*)")

SECTION = "## Wirkung auf Daten und Behördenunterlagen\n\n"
NO_IMPACT = (
    SECTION + "- [ ] Neue Sichtbarkeit\n"
    "- [x] Keine Wirkung auf Daten, Sichtbarkeit, Auswertung, Datenflüsse oder Voreinstellungen\n"
    "\n## Checkliste\n\n- [x] Tests bestehen lokal\n"
)
IMPACT = (
    SECTION
    + "- [X] Neue Sichtbarkeit: Daten werden für weitere Rollen sichtbar\n- [ ] Keine Wirkung\n"
)
UNTICKED = (
    SECTION
    + "- [ ] Neue Sichtbarkeit\n- [ ] Keine Wirkung\n\n## Checkliste\n\n- [x] Keine Wirkung\n"
)


def git(repo: Path, *args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=repo, check=True, capture_output=True, text=True
    ).stdout.strip()


def write(repo: Path, name: str, text: str = "x\n") -> None:
    target = repo / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding="utf-8")


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    git(tmp_path, "init", "-q")
    git(tmp_path, "config", "user.email", "probe@example.org")
    git(tmp_path, "config", "user.name", "probe")
    write(
        tmp_path,
        ".github/data-impact-paths.txt",
        "# comment\n\nbackend/src/main/resources/db/changelog/**\nbackend/src/main/java/**/*Properties.java\n",
    )
    write(tmp_path, "README.md")
    git(tmp_path, "add", "-A")
    git(tmp_path, "commit", "-q", "-m", "base")
    return tmp_path


def change(repo: Path, *names: str) -> None:
    for name in names:
        write(repo, name, "changed\n")
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "-m", "change")


def run(repo: Path, body: str = "", label: bool = False) -> subprocess.CompletedProcess:
    env = {**os.environ, "HAS_LABEL": "true" if label else "false", "PR_BODY": body}
    env.pop("GITHUB_STEP_SUMMARY", None)
    return subprocess.run(
        [BASH, SCRIPT.as_posix(), "HEAD^1", "HEAD"],
        check=False,
        cwd=repo,
        capture_output=True,
        text=True,
        env=env,
    )


def test_matched_path_without_label_or_statement_fails(repo: Path) -> None:
    change(repo, "backend/src/main/resources/db/changelog/x/2026-10-15-new.yaml")
    result = run(repo)
    assert result.returncode == 1
    assert "::error::Files with possible data impact" in result.stdout
    assert "db/changelog/x/2026-10-15-new.yaml" in result.stdout


def test_double_star_spans_directories(repo: Path) -> None:
    change(repo, "backend/src/main/java/io/opaa/chat/ChatNoteProperties.java")
    assert run(repo).returncode == 1


def test_label_passes(repo: Path) -> None:
    change(repo, "backend/src/main/resources/db/changelog/x/new.yaml")
    result = run(repo, label=True)
    assert result.returncode == 0
    assert "::notice::" in result.stdout


def test_ticked_no_impact_passes_with_warning(repo: Path) -> None:
    change(repo, "backend/src/main/resources/db/changelog/x/new.yaml")
    result = run(repo, body=NO_IMPACT)
    assert result.returncode == 0
    assert "::warning::" in result.stdout


def test_crlf_body_is_read(repo: Path) -> None:
    change(repo, "backend/src/main/resources/db/changelog/x/new.yaml")
    assert run(repo, body=NO_IMPACT.replace("\n", "\r\n")).returncode == 0


def test_no_impact_ticked_outside_section_does_not_count(repo: Path) -> None:
    change(repo, "backend/src/main/resources/db/changelog/x/new.yaml")
    assert run(repo, body=UNTICKED).returncode == 1


def test_ticked_impact_without_label_fails_even_without_matched_path(
    repo: Path,
) -> None:
    change(repo, "frontend/src/features/x.tsx")
    result = run(repo, body=IMPACT)
    assert result.returncode == 1
    assert "label data-impact is missing" in result.stdout


def test_ticked_impact_with_label_passes(repo: Path) -> None:
    change(repo, "frontend/src/features/x.tsx")
    assert run(repo, body=IMPACT, label=True).returncode == 0


def test_unmatched_path_passes(repo: Path) -> None:
    change(repo, "frontend/src/features/x.tsx", "docs/x.md")
    result = run(repo)
    assert result.returncode == 0
    assert "No changed file matches" in result.stdout


def patterns() -> list[str]:
    return [
        line.strip()
        for line in PATHS.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.startswith("#")
    ]


def tracked(*pathspecs: str) -> set[str]:
    return set(git(REPO_ROOT, "ls-files", "--", *pathspecs).split())


def marker(path: str) -> str | None:
    if path.startswith("backend/src/main/resources/") and "prompt" in path.lower():
        return "prompt resource"
    if not path.endswith(".java"):
        return None
    if RETENTION_NAME.search(Path(path).name):
        return "retention class"
    code = "\n".join(
        line
        for line in (REPO_ROOT / path).read_text(encoding="utf-8").splitlines()
        if not COMMENT_LINE.match(line)
    )
    for name, pattern in (
        ("data flow", FLOW),
        ("prompt", PROMPT),
        ("retention", RETENTION),
    ):
        found = pattern.search(code)
        if found:
            return f"{name}: {found.group(0).strip()}"
    return None


def test_every_pattern_matches_a_tracked_file() -> None:
    assert patterns()
    stale = [p for p in patterns() if not tracked(f":(glob){p}")]
    assert stale == [], f"patterns without a tracked file in {PATHS.name}: {stale}"


def test_every_data_flow_prompt_and_retention_file_is_covered() -> None:
    covered = tracked(*(f":(glob){p}" for p in patterns()))
    candidates = tracked(
        ":(glob)backend/src/main/java/**/*.java", ":(glob)backend/src/main/resources/**"
    )
    missing = sorted(
        f"{path} ({reason})"
        for path in candidates - covered
        if (reason := marker(path)) is not None
    )
    assert missing == [], f"add a pattern to {PATHS.name} for: {missing}"


def test_marker_detects_flow_prompt_and_retention() -> None:
    assert marker("backend/src/main/resources/prompts/answer.st") == "prompt resource"
    assert (
        marker("backend/src/main/java/io/opaa/x/FooRetentionService.java")
        == "retention class"
    )
    assert FLOW.search("private final RestClient client;")
    assert PROMPT.search('static final String SYSTEM_PROMPT = """')
    assert not PROMPT.search("ConnectionLogActor.system()")
    assert RETENTION.search("static final Duration RETENTION = Duration.ofDays(14);")
    assert not RETENTION.search(
        "static final Duration HANDOVER_TTL = Duration.ofHours(1);"
    )


def test_renovate_exception_names_the_renovate_account() -> None:
    renovate = (REPO_ROOT / "renovate.json5").read_text(encoding="utf-8")
    author = re.search(r"gitAuthor:\s*'[^<]*<\d+\+([^@]+)@", renovate)
    assert author, "gitAuthor in renovate.json5 no longer names a noreply account"
    workflow = (REPO_ROOT / ".github/workflows/data-impact.yml").read_text(
        encoding="utf-8"
    )
    accounts = re.search(
        r"fromJSON\('(\[[^']*\])'\),\s*github\.event\.pull_request\.user\.login",
        workflow,
    )
    assert accounts, "data-impact.yml no longer checks the Renovate author"
    assert author.group(1) in json.loads(accounts.group(1))


TEMPLATE = REPO_ROOT / ".github" / "PULL_REQUEST_TEMPLATE.md"


def template_boxes() -> list[str]:
    section = (
        TEMPLATE.read_text(encoding="utf-8")
        .split("## Wirkung auf Daten")[1]
        .split("\n## ")[0]
    )
    return [line for line in section.splitlines() if line.startswith("- [ ] ")]


def test_template_lists_ai_classification_box() -> None:
    assert any("KI-Einstufung" in box for box in template_boxes())


def test_every_ticked_template_impact_box_needs_the_label(repo: Path) -> None:
    change(repo, "frontend/src/features/x.tsx")
    body = TEMPLATE.read_text(encoding="utf-8")
    impact_boxes = [box for box in template_boxes() if "Keine Wirkung" not in box]
    assert len(impact_boxes) >= 7
    for box in impact_boxes:
        ticked = body.replace(box, box.replace("[ ]", "[x]", 1))
        assert run(repo, body=ticked).returncode == 1, box
        assert run(repo, body=ticked, label=True).returncode == 0, box


def test_ticked_template_no_impact_box_passes(repo: Path) -> None:
    change(repo, "backend/src/main/resources/db/changelog/x/new.yaml")
    body = TEMPLATE.read_text(encoding="utf-8")
    (no_impact,) = [box for box in template_boxes() if "Keine Wirkung" in box]
    assert run(repo, body=body).returncode == 1
    assert (
        run(
            repo, body=body.replace(no_impact, no_impact.replace("[ ]", "[x]", 1))
        ).returncode
        == 0
    )
