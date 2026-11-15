"""Self-check for check_compose_defaults.sh: the repository's own Compose templates pass, and each
rule fails on a copy of them with one change - a port on 0.0.0.0 or without host (also in a list
at the key's own indentation), a bind variable that opens by default, the long port syntax without
host_ip 127.0.0.1, ports or environment in flow style, network_mode: host, a bind address beyond
loopback in an environment template, an enabled OPAA_AUTH_DEV_ALLOW_REMOTE, a frontend bind
address the backend does not learn, a fixed secret in any spelling or in a service its exception
does not name, an exception that no longer matches, a port, even on loopback, of a service
that must stay unpublished or an entry for such a service that is gone, a hardened service
without read-only root file system, cap_drop ALL or no-new-privileges, or whose entry is gone, an
isolated network without internal: true or with traffic between its containers allowed, and a
service in the host's network namespace that listens on anything but the gateway of an internal
network of its file, whose healthcheck, URL variable or sandbox network disagrees with its config,
or whose entry is gone."""

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


# Read by the check for the bind address of a service in the host's network namespace.
SERVER_CONFIG = "sandbox/config.toml"


@pytest.fixture
def repo(tmp_path: Path) -> Path:
    subprocess.run(["git", "init", "-q"], cwd=tmp_path, check=True)
    for name in [*templates(), SERVER_CONFIG]:
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


def test_an_unpublished_service_with_a_loopback_port_fails(repo: Path) -> None:
    replace(
        repo,
        "docker-compose.ocr.yml",
        "    restart: unless-stopped\n",
        '    restart: unless-stopped\n    ports:\n      - "127.0.0.1:5001:5001"\n',
    )
    fails_with(run(repo), "opaa-ocr publishes a port")


def test_an_unpublished_service_with_no_ports_passes(repo: Path) -> None:
    replace(
        repo,
        "docker-compose.ocr.yml",
        "    restart: unless-stopped\n",
        "    restart: unless-stopped\n    ports: !reset []\n",
    )
    result = run(repo)
    assert result.returncode == 0, result.stdout


def test_an_unpublished_service_that_is_gone_fails(repo: Path) -> None:
    replace(repo, "docker-compose.ocr.yml", "  opaa-ocr:\n", "  ocr-renamed:\n")
    fails_with(run(repo), "UNPUBLISHED_SERVICES names the service opaa-ocr")


@pytest.mark.parametrize(
    ("old", "new", "missing"),
    [
        ("    read_only: true\n", "    read_only: false\n", "read_only: true"),
        ("    read_only: true\n", "", "read_only: true"),
        ("    cap_drop:\n      - ALL\n", "    cap_drop:\n      - NET_RAW\n", "cap_drop: [ALL]"),
        ("    cap_drop:\n      - ALL\n", "", "cap_drop: [ALL]"),
        ("      - no-new-privileges:true\n", "      - no-new-privileges:false\n",
         "security_opt: [no-new-privileges:true]"),
        ("    security_opt:\n      - no-new-privileges:true\n", "",
         "security_opt: [no-new-privileges:true]"),
    ],
)
def test_a_hardened_service_without_one_of_its_settings_fails(
    repo: Path, old: str, new: str, missing: str
) -> None:
    replace(repo, "docker-compose.sandbox.yml", old, new)
    fails_with(run(repo), f"opensandbox-server lacks {missing}")


def test_a_hardening_setting_of_another_service_does_not_count(repo: Path) -> None:
    # The backend service of the same file follows; its settings belong to it alone.
    replace(repo, "docker-compose.sandbox.yml", "    read_only: true\n", "")
    replace(
        repo,
        "docker-compose.sandbox.yml",
        "  backend:\n    environment:\n",
        "  backend:\n    read_only: true\n    environment:\n",
    )
    fails_with(run(repo), "opensandbox-server lacks read_only: true")


def test_a_hardened_service_in_flow_style_passes(repo: Path) -> None:
    replace(repo, "docker-compose.sandbox.yml", "    cap_drop:\n      - ALL\n", "    cap_drop: [ALL]\n")
    replace(
        repo,
        "docker-compose.sandbox.yml",
        "    security_opt:\n      - no-new-privileges:true\n",
        '    security_opt: ["no-new-privileges:true"]\n',
    )
    result = run(repo)
    assert result.returncode == 0, result.stdout


def test_a_flow_style_security_opt_without_no_new_privileges_fails(repo: Path) -> None:
    replace(
        repo,
        "docker-compose.sandbox.yml",
        "    security_opt:\n      - no-new-privileges:true\n",
        "    security_opt: [label:disable]\n",
    )
    fails_with(run(repo), "opensandbox-server lacks security_opt: [no-new-privileges:true]")


@pytest.mark.parametrize(
    ("addition", "named"),
    [
        ("    privileged: true\n", "privileged: true"),
        ("    cap_add:\n      - SYS_ADMIN\n", "cap_add: SYS_ADMIN"),
        ("    cap_add: [NET_RAW]\n", "cap_add: NET_RAW"),
        ("    pid: host\n", "pid: host"),
        ("    ipc: host\n", "ipc: host"),
    ],
)
def test_a_hardened_service_that_loosens_its_hardening_fails(
    repo: Path, addition: str, named: str
) -> None:
    replace(repo, "docker-compose.sandbox.yml", "    read_only: true\n",
            "    read_only: true\n" + addition)
    result = run(repo)
    fails_with(result, "opensandbox-server undoes its hardening: line ")
    fails_with(result, named)


@pytest.mark.parametrize(
    "profile", ["seccomp:unconfined", "apparmor:unconfined", "seccomp=unconfined"]
)
def test_a_hardened_service_with_an_unconfined_profile_fails(repo: Path, profile: str) -> None:
    replace(repo, "docker-compose.sandbox.yml", "      - no-new-privileges:true\n",
            f"      - no-new-privileges:true\n      - {profile}\n")
    fails_with(run(repo), f"security_opt: {profile}")


def test_an_unconfined_profile_in_flow_style_fails(repo: Path) -> None:
    replace(
        repo,
        "docker-compose.sandbox.yml",
        "    security_opt:\n      - no-new-privileges:true\n",
        "    security_opt: [no-new-privileges:true, seccomp:unconfined]\n",
    )
    fails_with(run(repo), "security_opt: seccomp:unconfined")


def test_a_hardened_service_that_is_gone_fails(repo: Path) -> None:
    replace(repo, "docker-compose.sandbox.yml", "  opensandbox-server:\n", "  sandbox-server:\n")
    fails_with(run(repo), "HARDENED_SERVICES names the service opensandbox-server")


def test_a_hardened_service_in_the_host_network_fails_unless_listed(repo: Path) -> None:
    replace(repo, "docker-compose.ocr.yml", "    read_only: true\n",
            "    read_only: true\n    network_mode: host\n")
    result = run(repo)
    fails_with(result, "opaa-ocr undoes its hardening: line ")
    fails_with(result, "network_mode: host")


@pytest.mark.parametrize(
    ("old", "new"),
    [
        ('    driver_opts:\n      com.docker.network.bridge.enable_icc: "false"\n', ""),
        ('com.docker.network.bridge.enable_icc: "false"', 'com.docker.network.bridge.enable_icc: "true"'),
        ('    driver_opts:\n      com.docker.network.bridge.enable_icc: "false"\n',
         '    driver_opts: {com.docker.network.bridge.enable_icc: "false"}\n'),
    ],
)
def test_the_sandbox_network_must_keep_its_containers_apart(repo: Path, old: str, new: str) -> None:
    replace(repo, "docker-compose.sandbox.yml", old, new)
    fails_with(run(repo), 'sandboxes lacks driver_opts com.docker.network.bridge.enable_icc: "false"')


def test_the_sandbox_network_must_stay_internal(repo: Path) -> None:
    # sandbox-api below stays internal; its setting must not count for sandboxes.
    replace(repo, "docker-compose.sandbox.yml", "    name: opaa-sandboxes\n    internal: true\n",
            "    name: opaa-sandboxes\n")
    fails_with(run(repo), "sandboxes lacks internal: true")


def test_an_isolated_network_that_is_gone_fails(repo: Path) -> None:
    replace(repo, "docker-compose.sandbox.yml", "  sandboxes:\n    name:", "  sandbox-net:\n    name:")
    fails_with(run(repo), "ISOLATED_NETWORKS names the network sandboxes")


@pytest.mark.parametrize(
    "bind", ['"0.0.0.0"', '"::"', '""', '"[::]"', '"0"', '"192.168.1.10"', '"172.31.255.250"']
)
def test_a_host_network_service_off_its_gateway_fails(repo: Path, bind: str) -> None:
    replace(repo, SERVER_CONFIG, 'host = "172.31.255.249"', f"host = {bind}")
    fails_with(run(repo), "opensandbox-server runs in the host's network namespace")


def test_a_host_network_service_without_bind_address_fails(repo: Path) -> None:
    replace(repo, SERVER_CONFIG, 'host = "172.31.255.249"\n', "")
    fails_with(run(repo), "opensandbox-server runs in the host's network namespace")


def test_a_host_network_service_that_left_the_host_network_fails(repo: Path) -> None:
    replace(repo, "docker-compose.sandbox.yml", "    network_mode: host\n", "")
    fails_with(run(repo), "HOST_NETWORK_SERVICES names the service opensandbox-server")


def test_a_host_network_service_on_the_gateway_of_a_network_with_egress_fails(repo: Path) -> None:
    replace(repo, "docker-compose.sandbox.yml", "    name: opaa-sandbox-api\n    internal: true\n",
            "    name: opaa-sandbox-api\n")
    fails_with(run(repo), "not the gateway of an internal: true network")


def test_a_healthcheck_on_another_address_fails(repo: Path) -> None:
    replace(repo, "docker-compose.sandbox.yml", "urlopen('http://172.31.255.249:8090/health'",
            "urlopen('http://127.0.0.1:8090/health'")
    fails_with(run(repo), "The healthcheck of opensandbox-server does not call 172.31.255.249")


@pytest.mark.parametrize(
    "url", ["http://opensandbox-server:8090", "http://172.31.255.2490:8090", "http://172x31.255.249:8090"]
)
def test_a_base_url_on_another_address_fails(repo: Path, url: str) -> None:
    replace(repo, "docker-compose.sandbox.yml", "OPAA_SANDBOX_BASE_URL: http://172.31.255.249:8090",
            f"OPAA_SANDBOX_BASE_URL: {url}")
    fails_with(run(repo), "OPAA_SANDBOX_BASE_URL in docker-compose.sandbox.yml does not name 172.31.255.249")


@pytest.mark.parametrize("mode", ['"bridge"', '"opaa-sandbox-api"', '""'])
def test_sandboxes_outside_the_isolated_network_fail(repo: Path, mode: str) -> None:
    replace(repo, SERVER_CONFIG, 'network_mode = "opaa-sandboxes"', f"network_mode = {mode}")
    fails_with(run(repo), "[docker] network_mode in sandbox/config.toml is")
