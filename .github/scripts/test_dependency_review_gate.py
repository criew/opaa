"""Self-check for dependency_review_gate.py.

Pins the wait for the head snapshots with a fresh per_page per request, the union over all
samples, the Link pagination, the critical/runtime/added filter, the fail-open path without
snapshots and the retry and failure behaviour of the HTTP client.
"""

import base64
import importlib.util
import io
import urllib.error
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parent / "dependency_review_gate.py"
SPEC = importlib.util.spec_from_file_location("dependency_review_gate", SCRIPT)
gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gate)

MISSING = {gate.WARNINGS_HEADER: base64.b64encode(b"No snapshots were found for the head SHA abc.")}


def change(name, severity=None, scope="runtime", change_type="added"):
    vulnerabilities = [] if severity is None else [
        {"severity": severity, "advisory_ghsa_id": f"GHSA-{name}", "advisory_summary": "bad"}
    ]
    return {
        "change_type": change_type,
        "manifest": "frontend/pnpm-lock.yaml",
        "name": name,
        "version": "1.0.0",
        "scope": scope,
        "vulnerabilities": vulnerabilities,
    }


class FakeApi:
    def __init__(self, responses):
        self.responses = list(responses)
        self.urls = []

    def get(self, url):
        self.urls.append(url)
        return self.responses.pop(0)


class Clock:
    def __init__(self):
        self.now = 0.0

    def __call__(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


@pytest.fixture(autouse=True)
def repo(monkeypatch):
    monkeypatch.setenv("REPO", "o/r")


def run(responses):
    api, clock, lines = FakeApi(responses), Clock(), []
    code = gate.run(api, "base", "head", sleep=clock.sleep, clock=clock, out=lines.append)
    return code, api, lines


def test_waits_for_snapshots_with_a_fresh_per_page_and_fails_on_a_finding_in_any_sample():
    clean = ({}, [change("minimist")])
    late = ({}, [change("minimist", "critical")])
    code, api, lines = run([(MISSING, []), (MISSING, []), clean, clean, clean, late, clean, clean])

    assert code == 1
    assert lines == ["::error::frontend/pnpm-lock.yaml » minimist@1.0.0: GHSA-minimist bad"]
    per_pages = [url.rsplit("per_page=", 1)[1] for url in api.urls]
    assert len(per_pages) == 2 + gate.SAMPLES
    assert len(set(per_pages)) == len(per_pages)


def test_passes_when_no_sample_shows_a_critical_runtime_addition():
    responses = [({}, [
        change("dev-only", "critical", scope="development"),
        change("removed", "critical", change_type="removed"),
        change("moderate", "moderate"),
        {**change("no-list"), "vulnerabilities": None},
    ])] * gate.SAMPLES
    code, _, lines = run(responses)

    assert code == 0
    assert lines == [f"Keine neue critical-Schwachstelle im Scope runtime ({gate.SAMPLES} Stichproben)."]


def test_follows_the_link_header_to_later_pages():
    first = ({"link": '<https://api.github.com/x?page=2>; rel="next", <https://api.github.com/x?page=2>; rel="last"'},
             [change("a")])
    second = ({}, [change("b", "critical")])
    code, api, _ = run([first, second] + [({}, [])] * (gate.SAMPLES - 1))

    assert code == 1
    assert api.urls[1] == "https://api.github.com/x?page=2"


def test_without_snapshots_warns_and_checks_the_graph_github_knows():
    polls = gate.WAIT_SECONDS // gate.POLL_INTERVAL + 1
    code, api, lines = run([(MISSING, [change("x", "critical")])] * polls)

    assert code == 1
    assert lines[0].startswith("::warning::Keine Snapshots für head")
    assert len(api.urls) == polls


def test_annotation_text_from_the_graph_is_escaped():
    assert gate.escape_annotation("a%b\r\nc") == "a%25b%0D%0Ac"


class Response(io.BytesIO):
    def __init__(self, body, headers):
        super().__init__(body)
        self.headers = headers

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False


def http_error(code, headers=None):
    return urllib.error.HTTPError("u", code, "e", headers or {}, io.BytesIO(b""))


def test_client_retries_rate_limits_and_server_errors():
    attempts = [
        http_error(502),
        http_error(429),
        http_error(403, {"x-ratelimit-remaining": "0"}),
        Response(b"[]", {"Link": "x"}),
    ]

    def opener(request, timeout):
        result = attempts.pop(0)
        if isinstance(result, Exception):
            raise result
        return result

    api = gate.Api("https://api.github.com", "t", sleep=lambda s: None, opener=opener)
    assert api.get("repos/o/r") == ({"link": "x"}, [])


def test_client_raises_on_other_errors():
    def opener(request, timeout):
        raise http_error(403)

    api = gate.Api("https://api.github.com", "t", sleep=lambda s: None, opener=opener)
    with pytest.raises(urllib.error.HTTPError):
        api.get("repos/o/r")
