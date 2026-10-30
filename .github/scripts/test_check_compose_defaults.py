"""Self-check for check_compose_defaults.sh: the repository's own Compose templates pass, and each
rule fails on a copy of them with one change - a port on 0.0.0.0 or without host (also in a list
at the key's own indentation), a bind variable that opens by default, the long port syntax without
host_ip 127.0.0.1, ports or environment in flow style, network_mode: host, a bind address beyond
loopback in an environment template, an enabled OPAA_AUTH_DEV_ALLOW_REMOTE, a frontend bind
address the backend does not learn, a fixed secret in any spelling or in a service its exception
does not name, and an exception that no longer matches."""

import shutil
import subprocess
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parent / "check_compose_defaults.sh"
ROOT = SCRIPT.parents[2]
BASH = shutil.which("bash") or "bash"
# Assembled so that SecretValidatorTest, which scans the repository for signing secrets, does not
# read the deliberately weak fixture values below as configured secrets.
JWT = "OPAA_AUTH_" + "JWT_SECRET"


def templates() -> list[str]:
    listed = subprocess.run(
        ["git", "ls-files", "--", "docker-compose*.yml", "**/docker-compose*.yml",
         ".env.docker.example", ".env.example"],
        cwd=ROOT, check=True, capture_output=True, text=True,
    )
    return listed.stdout.split()


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    subprocess.run(["git", "init", "-q"], cwd=tmp_path, check=True)
    for name in templates():
        target = tmp_path / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / name, target)
    return tmp_path


def run(repo: Path) -> subprocess.CompletedProcess:
    subprocess.run(["git", "add", "-A"], cwd=repo, check=True)
    return subprocess.run([BASH, SCRIPT.as_posix()], cwd=repo, capture_output=True, text=True)


def replace(repo: Path, name: str, old: str, new: str) -> None:
    path = repo / name
    text = path.read_text(encoding="utf-8")
    assert old in text, f"{old!r} is not in {name}"
    path.write_text(text.replace(old, new, 1), encoding="utf-8")


def append(repo: Path, name: str, text: str) -> None:
    with (repo / name).open("a", encoding="utf-8") as file:
        file.write(text)


def fails_with(result: subprocess.CompletedProcess, message: str) -> None:
    assert result.returncode == 1, result.stdout
    assert message in result.stdout, result.stdout


def test_the_repository_templates_pass(repo: Path) -> None:
    result = run(repo)
    assert result.returncode == 0, result.stdout
    assert "Compose templates:" in result.stdout


@pytest.mark.parametrize(
    "port",
    [
        '"0.0.0.0:${OPAA_DB_PORT:-5432}:5432"',
        '"${OPAA_DB_PORT:-5432}:5432"',
        "5432",
        '"${OPAA_FRONTEND_BIND_ADDRESS:-0.0.0.0}:5432:5432"',
        '"${OPAA_DB_BIND_ADDRESS:-127.0.0.1}:5432:5432"',
        '"[::]:5432:5432"',
    ],
)
def test_a_port_beyond_loopback_fails(repo: Path, port: str) -> None:
    replace(repo, "docker-compose.yml", '"127.0.0.1:${OPAA_DB_PORT:-5432}:5432"', port)
    fails_with(run(repo), "is published beyond loopback")


def test_a_port_beyond_loopback_in_a_test_stack_fails(repo: Path) -> None:
    replace(
        repo,
        "e2e/docker-compose.e2e.yml",
        '"127.0.0.1:${OPAA_AI_STUB_PORT:-18089}:8089"',
        '"${OPAA_AI_STUB_PORT:-18089}:8089"',
    )
    fails_with(run(repo), "file=e2e/docker-compose.e2e.yml")


def test_the_long_syntax_needs_host_ip(repo: Path) -> None:
    long_syntax = "- target: 5432\n        published: 5432\n"
    replace(repo, "docker-compose.yml", '- "127.0.0.1:${OPAA_DB_PORT:-5432}:5432"', long_syntax)
    fails_with(run(repo), "without host_ip: 127.0.0.1")


def test_a_list_at_the_keys_own_indentation_is_read(repo: Path) -> None:
    replace(
        repo,
        "docker-compose.yml",
        'ports:\n      - "127.0.0.1:${OPAA_DB_PORT:-5432}:5432"',
        'ports:\n    - "0.0.0.0:5432:5432"',
    )
    fails_with(run(repo), "The port 0.0.0.0:5432:5432 is published beyond loopback")


def test_host_ip_must_be_loopback_exactly(repo: Path) -> None:
    long_syntax = "- target: 5432\n        published: 5432\n        host_ip: 127.0.0.10\n"
    replace(repo, "docker-compose.yml", '- "127.0.0.1:${OPAA_DB_PORT:-5432}:5432"', long_syntax)
    fails_with(run(repo), "host_ip '127.0.0.10'")


def test_the_long_syntax_with_host_ip_passes(repo: Path) -> None:
    long_syntax = "- target: 5432\n        published: 5432\n        host_ip: 127.0.0.1\n"
    replace(repo, "docker-compose.yml", '- "127.0.0.1:${OPAA_DB_PORT:-5432}:5432"', long_syntax)
    result = run(repo)
    assert result.returncode == 0, result.stdout


def test_ports_in_flow_style_fail(repo: Path) -> None:
    replace(
        repo,
        "docker-compose.yml",
        'ports:\n      - "127.0.0.1:${OPAA_DB_PORT:-5432}:5432"',
        'ports: ["0.0.0.0:5432:5432"]',
    )
    fails_with(run(repo), "flow style")


def test_environment_in_flow_style_fails(repo: Path) -> None:
    replace(
        repo,
        "docker-compose.yml",
        "    environment:\n      POSTGRES_DB: opaa\n",
        "    environment: {" + JWT + ": fest}\n    x-was-environment:\n"
        "      POSTGRES_DB: opaa\n",
    )
    fails_with(run(repo), "environment: in flow style")


def test_host_networking_fails(repo: Path) -> None:
    replace(repo, "docker-compose.yml", "    restart: unless-stopped\n", "    network_mode: host\n")
    fails_with(run(repo), "network_mode: host")


@pytest.mark.parametrize(
    ("name", "line"),
    [
        (".env.docker.example", "OPAA_AUTH_DEV_ALLOW_REMOTE=true\n"),
        (".env.docker.example", "OPAA_AUTH_DEV_ALLOW_REMOTE=TRUE\n"),
        ("docker-compose.multi.yml", '      OPAA_AUTH_DEV_ALLOW_REMOTE: "true"\n'),
        ("docker-compose.multi.yml", "      OPAA_AUTH_DEV_ALLOW_REMOTE: ${ALLOW:-true}\n"),
    ],
)
def test_anonymous_administration_beyond_loopback_fails(repo: Path, name: str, line: str) -> None:
    append(repo, name, line)
    fails_with(run(repo), "OPAA_AUTH_DEV_ALLOW_REMOTE=true")


def test_the_backend_must_learn_the_frontend_bind_address(repo: Path) -> None:
    replace(
        repo,
        "docker-compose.yml",
        "OPAA_AUTH_DEV_PUBLISHED_ADDRESS: ${OPAA_FRONTEND_BIND_ADDRESS:-127.0.0.1}",
        "OPAA_AUTH_DEV_PUBLISHED_ADDRESS: 127.0.0.1",
    )
    fails_with(run(repo), "does not get OPAA_AUTH_DEV_PUBLISHED_ADDRESS")


@pytest.mark.parametrize(
    ("name", "line"),
    [
        (".env.docker.example", JWT + "=ein-fester-wert\n"),
        (".env.docker.example", 'OPAA_SETTINGS_ENCRYPTION_KEY="QUJD"\n'),
        ("docker-compose.multi.yml", "      " + JWT + ": ein-fester-wert\n"),
        ("docker-compose.multi.yml", "      OPAA_INSTALLATION_TOKEN: ${TOKEN:-abc}\n"),
        ("docker-compose.multi.yml", '      - "OPAA_CREDENTIALS_ENCRYPTION_KEY=QUJD"\n'),
        ("e2e/docker-compose.offline.yml", "      MINIO_ROOT_PASSWORD: geheim\n"),
        (".env.example", JWT + "=ein-fester-wert\n"),
    ],
)
def test_a_fixed_secret_fails(repo: Path, name: str, line: str) -> None:
    append(repo, name, line)
    fails_with(run(repo), "carries the fixed value")


def test_an_exception_covers_its_own_service_only(repo: Path) -> None:
    replace(
        repo,
        "docker-compose.yml",
        "    environment:\n      POSTGRES_DB: opaa\n",
        "    environment:\n      POSTGRES_DB: opaa\n      KC_BOOTSTRAP_ADMIN_PASSWORD: admin\n",
    )
    fails_with(run(repo), "KC_BOOTSTRAP_ADMIN_PASSWORD carries the fixed value 'admin'")


@pytest.mark.parametrize(
    ("name", "line"),
    [
        (".env.example", "OPAA_FRONTEND_BIND_ADDRESS=0.0.0.0\n"),
        (".env.docker.example", "OPAA_KEYCLOAK_BIND_ADDRESS=192.168.1.10\n"),
    ],
)
def test_a_bind_address_beyond_loopback_in_an_environment_template_fails(
    repo: Path, name: str, line: str
) -> None:
    append(repo, name, line)
    fails_with(run(repo), "opens a published port beyond loopback")


def test_a_loopback_bind_address_in_an_environment_template_passes(repo: Path) -> None:
    append(repo, ".env.docker.example", "OPAA_FRONTEND_BIND_ADDRESS=127.0.0.1\n")
    result = run(repo)
    assert result.returncode == 0, result.stdout


@pytest.mark.parametrize(
    "line",
    [
        JWT + "=\n",
        JWT + "=${" + JWT + "}\n",
        JWT + "=${" + JWT + ":?generate one}\n",
        "# " + JWT + "=ein-fester-wert\n",
        "OPAA_INDEXING_EVENTS_MAX_PENDING_KEYS=500\n",
    ],
)
def test_an_open_secret_passes(repo: Path, line: str) -> None:
    append(repo, ".env.docker.example", line)
    result = run(repo)
    assert result.returncode == 0, result.stdout


def test_an_exception_that_matches_nothing_fails(repo: Path) -> None:
    replace(
        repo,
        "docker-compose.yml",
        "POSTGRES_PASSWORD: ${OPAA_DB_PASSWORD:-opaa}",
        "POSTGRES_PASSWORD: ${OPAA_DB_PASSWORD:?set a password}",
    )
    fails_with(run(repo), "carries no fixed value there any more")
