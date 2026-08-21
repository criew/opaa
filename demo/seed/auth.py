"""Authentication providers for the seed script (Issue #712).

Both data profiles ("demo" and "e2e") share the same seed mechanism - only how a session for a
given user is obtained differs, exactly the split docs/features/demo-instance.md draws under
"Installation und Seed": "Geteilt sind die Daten, nicht die Nutzerbereitstellung."

- DevHeaderAuth: the "e2e" (and any local dev) profile authenticates every request as one of the
  users configured under opaa.auth.dev.users, selected per request via the X-OPAA-Dev-User header
  (see backend/src/main/java/io/opaa/auth/DevAuthFilter.java). No token, no password.
- KeycloakPasswordAuth: the "demo" profile authenticates against a real OIDC provider. The seed
  script itself is not a browser, so it cannot run the frontend's authorization-code + PKCE flow -
  it uses the Resource Owner Password Credentials grant instead, against a dedicated "opaa-seed"
  client (keycloak/realm-export.json) that has directAccessGrantsEnabled=true. opaa-frontend keeps
  directAccessGrantsEnabled=false; this client exists solely for the seed script, never for a real
  login.
"""

from __future__ import annotations

import time
from dataclasses import dataclass

import requests


class AuthError(RuntimeError):
    pass


@dataclass
class DevHeaderAuth:
    """Auth provider for the dev auth profile (X-OPAA-Dev-User header, no token)."""

    subject: str

    def headers(self) -> dict[str, str]:
        return {"X-OPAA-Dev-User": self.subject}


class KeycloakPasswordAuth:
    """Auth provider for the demo profile: Resource Owner Password Credentials grant.

    Tokens are fetched lazily and refreshed shortly before they expire, so a long-running seed
    (indexing triggers are rate-limited, see api_client.py) does not fail partway through with an
    expired bearer token.
    """

    def __init__(
        self,
        keycloak_url: str,
        realm: str,
        client_id: str,
        username: str,
        password: str,
    ) -> None:
        self._token_endpoint = (
            f"{keycloak_url.rstrip('/')}/realms/{realm}/protocol/openid-connect/token"
        )
        self._client_id = client_id
        self._username = username
        self._password = password
        self._access_token: str | None = None
        self._expires_at: float = 0.0

    def headers(self) -> dict[str, str]:
        if self._access_token is None or time.monotonic() >= self._expires_at:
            self._fetch_token()
        return {"Authorization": f"Bearer {self._access_token}"}

    def _fetch_token(self) -> None:
        response = requests.post(
            self._token_endpoint,
            data={
                "grant_type": "password",
                "client_id": self._client_id,
                "username": self._username,
                "password": self._password,
                "scope": "openid",
            },
            timeout=30,
        )
        if response.status_code != 200:
            raise AuthError(
                f"Keycloak-Anmeldung für '{self._username}' fehlgeschlagen: "
                f"{response.status_code} {response.text[:300]}"
            )
        payload = response.json()
        self._access_token = payload["access_token"]
        # 30s safety margin so a request started just before expiry does not race the server's
        # own clock.
        self._expires_at = time.monotonic() + max(payload.get("expires_in", 60) - 30, 5)
