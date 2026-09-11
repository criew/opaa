"""Tests for the parts of the seed run that are checkable without a running stack (Issue #1515).

What is covered: how a failed readiness wait is reported (an unreachable stack and a rejected
token are different failures and must not share one message), and that the realm export keeps the
audience mapper the token path depends on. The seed run itself stays out - it is a sequence of API
calls against a live installation, covered by the nightly demo smoke run.

Run from the repository root:
    pytest demo/seed
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest
import requests

sys.path.insert(0, str(Path(__file__).parent))

import seed  # noqa: E402
from api_client import ApiError  # noqa: E402
from auth import AuthError  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]
REALM_EXPORT = REPO_ROOT / "keycloak" / "realm-export.json"

WWW_AUTHENTICATE = (
    'Bearer error="invalid_token", error_description="An error occurred while attempting to '
    "decode the Jwt: The azp claim names a different client than this provider's client id, and "
    'aud does not name it either"'
)


def response(
    status: int, *, body: str = "", headers: dict[str, str] | None = None
) -> requests.Response:
    """A requests.Response as the seed script sees it. Assigning _content is how requests itself
    builds a response outside a real transport; response.text reads from it."""
    built = requests.Response()
    built.status_code = status
    built._content = body.encode()
    built.headers.update(headers or {})
    built.request = requests.Request("GET", "http://localhost:8081/api/v1/auth/me").prepare()
    return built


class FailingClient:
    """Stands in for api_client.Client: every readiness probe raises the same error."""

    def __init__(self, error: Exception) -> None:
        self._error = error

    def get_ok(self, path: str, **kwargs):
        raise self._error


@pytest.fixture(autouse=True)
def no_sleep(monkeypatch: pytest.MonkeyPatch) -> None:
    """The readiness wait sleeps between probes; the tests below only care about its outcome."""
    monkeypatch.setattr(seed.time, "sleep", lambda _seconds: None)


def wait_message(error: Exception, auth_mode: str = "keycloak") -> str:
    with pytest.raises(SystemExit) as exit_info:
        seed.wait_until_ready(FailingClient(error), auth_mode, timeout_seconds=0.01)
    return str(exit_info.value)


def test_rejected_token_is_not_reported_as_unreachable() -> None:
    message = wait_message(
        ApiError(response(401, headers={"WWW-Authenticate": WWW_AUTHENTICATE}))
    )
    assert "abgelehnt" in message
    assert "nicht erreichbar" not in message
    assert "azp" in message
    assert "aud does not name it either" in message


def test_rejected_token_names_the_audience_mapper_as_the_usual_cause() -> None:
    message = wait_message(ApiError(response(403)))
    assert "opaa-seed" in message
    assert "Audience-Mapper" in message
    assert "client_id der Anbieterzeile" in message


def test_dev_auth_gets_its_own_cause_instead_of_the_keycloak_one() -> None:
    """DevAuthFilter answers an unknown X-OPAA-Dev-User with 401 as well - a stack without any
    Keycloak must not be told about tokens, clients and audience mappers."""
    message = wait_message(ApiError(response(401)), auth_mode="dev")
    assert "X-OPAA-Dev-User" in message
    assert "opaa.auth.dev.users" in message
    assert "keycloak" not in message.lower()
    assert "opaa-seed" not in message


def test_unknown_auth_mode_states_the_rejection_without_guessing_a_cause() -> None:
    message = wait_message(ApiError(response(401)), auth_mode="mtls")
    assert "abgelehnt" in message
    assert "opaa-seed" not in message
    assert "X-OPAA-Dev-User" not in message


def test_connection_error_is_reported_as_unreachable() -> None:
    message = wait_message(requests.exceptions.ConnectionError("connection refused"))
    assert "nicht erreichbar" in message
    assert "connection refused" in message


def test_read_timeout_is_reported_as_unreachable() -> None:
    """A timeout is an unreachable stack, not a crash: it must be caught like a connection error."""
    message = wait_message(requests.exceptions.ReadTimeout("read timed out"))
    assert "nicht erreichbar" in message


def test_failed_keycloak_login_names_keycloak() -> None:
    message = wait_message(AuthError("Keycloak-Anmeldung für 'demo-admin' fehlgeschlagen: 401"))
    assert "Keycloak" in message
    assert "nicht erreichbar" not in message


def test_backend_error_status_is_reported_with_its_status() -> None:
    message = wait_message(ApiError(response(500, body="Internal Server Error")))
    assert "500" in message
    assert "nicht erreichbar" not in message


def test_api_error_quotes_the_www_authenticate_header() -> None:
    """Without it a 401 carries no reason at all: Spring Security answers with an empty body."""
    error = ApiError(response(401, headers={"WWW-Authenticate": WWW_AUTHENTICATE}))
    assert "WWW-Authenticate" in str(error)
    assert "aud does not name it either" in str(error)


def test_api_error_without_challenge_stays_unchanged() -> None:
    assert "WWW-Authenticate" not in str(ApiError(response(404, body="not found")))


def test_seed_client_carries_an_audience_mapper_for_the_frontend_client() -> None:
    """The backend validates azp/aud against the client id of the provider row (ADR-0025); without
    this mapper every API call of a demo seed run against a keycloak-auth backend ends in 401."""
    realm = json.loads(REALM_EXPORT.read_text(encoding="utf-8"))
    seed_client = next(c for c in realm["clients"] if c["clientId"] == "opaa-seed")
    mappers = [
        mapper
        for mapper in seed_client.get("protocolMappers", [])
        if mapper["protocolMapper"] == "oidc-audience-mapper"
        and mapper["config"]["included.client.audience"] == "opaa-frontend"
    ]
    assert len(mappers) == 1
    assert mappers[0]["config"]["access.token.claim"] == "true"
