"""Thin HTTP client for the seed script (Issue #712).

Speaks only the public OPAA API (backend/src/main/resources/openapi/opaa-api.yaml) - no direct
database access, per the issue's own "Technische Hinweise": direct writes would bypass Liquibase
and domain logic (validation, audit events, quota checks, ...).
"""

from __future__ import annotations

import sys
import time

import requests


class ApiError(RuntimeError):
    def __init__(self, response: requests.Response) -> None:
        super().__init__(
            f"{response.request.method} {response.request.url} -> {response.status_code}: "
            f"{response.text[:500]}"
        )
        self.response = response
        self.status_code = response.status_code


class Client:
    """One authenticated session against the OPAA API for a single user.

    Retries transparently on 429 (opaa.rate-limit.*, see application.yml) - the seed triggers
    several indexing runs back to back as the same admin user, which the default per-user
    indexing limit (1 request/60s) would otherwise reject outright. No Retry-After header is sent
    (RateLimitFilter does not set one), so this waits a fixed, configurable window instead.
    """

    def __init__(
        self, base_url: str, auth, rate_limit_wait_seconds: int = 65, label: str = ""
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self._auth = auth
        self._rate_limit_wait_seconds = rate_limit_wait_seconds
        self._label = label
        self._session = requests.Session()

    def request(self, method: str, path: str, **kwargs) -> requests.Response:
        url = f"{self.base_url}{path}"
        while True:
            headers = dict(kwargs.pop("headers", None) or {})
            headers.update(self._auth.headers())
            response = self._session.request(method, url, headers=headers, timeout=60, **kwargs)
            if response.status_code == 429:
                who = f" ({self._label})" if self._label else ""
                print(
                    f"  … Rate-Limit erreicht bei {method} {path}{who}, "
                    f"warte {self._rate_limit_wait_seconds}s",
                    file=sys.stderr,
                )
                time.sleep(self._rate_limit_wait_seconds)
                continue
            return response

    def get(self, path: str, **kwargs) -> requests.Response:
        return self.request("GET", path, **kwargs)

    def post(self, path: str, **kwargs) -> requests.Response:
        return self.request("POST", path, **kwargs)

    def put(self, path: str, **kwargs) -> requests.Response:
        return self.request("PUT", path, **kwargs)

    def delete(self, path: str, **kwargs) -> requests.Response:
        return self.request("DELETE", path, **kwargs)

    def get_ok(self, path: str, **kwargs) -> dict | list:
        response = self.get(path, **kwargs)
        if not response.ok:
            raise ApiError(response)
        return response.json()

    def post_ok(self, path: str, expected: tuple[int, ...] = (200, 201, 202), **kwargs):
        response = self.post(path, **kwargs)
        if response.status_code not in expected:
            raise ApiError(response)
        return response.json() if response.content else None
