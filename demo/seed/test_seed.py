"""Tests for the parts of the seed run that are checkable without a running stack (Issue #1515).

What is covered: how a failed readiness wait is reported (an unreachable stack and a rejected
token are different failures and must not share one message), and that the realm export keeps the
audience mapper the token path depends on. The demo profile's groups and the effective permission
matrix they produce are checked as data, and the group steps run twice against an in-memory API to
show the second run writes nothing. The seed run itself stays out - it is a sequence of API calls
against a live installation, covered by the nightly demo smoke run.

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

import profiles  # noqa: E402
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


# --- Demo profile: groups and the effective permission matrix -----------------------------------

DEMO = profiles.DEMO_PROFILE
FACH_KEYS = ("maria", "selin", "thomas", "andrea")


def group(name: str) -> profiles.GroupDef:
    return next(g for g in DEMO.groups if g.name == name)


def library(name: str) -> profiles.LibraryDef:
    return next(lib for lib in DEMO.libraries if lib.name == name)


def space(name: str) -> profiles.SpaceDef:
    return next(s for s in DEMO.spaces if s.name == name)


def effective_readers(library_name: str) -> set[str]:
    """Direct VIEWER grants plus the members of every group granted VIEWER on the library."""
    readers = set(library(library_name).viewer_keys)
    for group_def in DEMO.groups:
        if library_name in group_def.library_grants:
            readers.update(group_def.member_keys)
    return readers


def space_members(space_name: str) -> set[str]:
    """Owner, individual members and the members of every group that is a member of the space."""
    space_def = space(space_name)
    members = {space_def.owner_key, *(m.user_key for m in space_def.members)}
    for group_def in DEMO.groups:
        if group_def.space_membership and group_def.space_membership[0] == space_name:
            members.update(group_def.member_keys)
    return members


def test_every_group_reference_resolves_within_the_profile() -> None:
    user_keys = {u.key for u in DEMO.all_users()}
    library_names = {lib.name for lib in DEMO.libraries}
    space_names = {s.name for s in DEMO.spaces}
    assert len({g.name for g in DEMO.groups}) == len(DEMO.groups)
    for group_def in DEMO.groups:
        assert set(group_def.steward_keys) <= user_keys
        assert set(group_def.member_keys) <= user_keys
        assert set(group_def.library_grants) <= library_names
        if group_def.space_membership:
            space_name, role = group_def.space_membership
            assert space_name in space_names
            assert role in ("MEMBER", "CURATOR", "ADMIN")


def test_presseverteiler_carries_the_press_read_exclusively_for_some_member() -> None:
    presseverteiler = group("Presseverteiler Bürgerbüro")
    assert presseverteiler.steward_keys == ("andrea",)
    assert presseverteiler.released_for_use
    assert presseverteiler.library_grants == ("Pressemitteilungen Stadt Rheinfurt",)
    direct = set(library("Pressemitteilungen Stadt Rheinfurt").viewer_keys)
    assert set(presseverteiler.member_keys) - direct, "no member reads exclusively via the group"


def test_a_group_brings_several_accounts_into_a_space_without_rows_of_their_own() -> None:
    sachbearbeitung = group("Sachbearbeitung Bürgerbüro")
    assert sachbearbeitung.released_for_use
    assert sachbearbeitung.space_membership is not None
    space_def = space(sachbearbeitung.space_membership[0])
    direct = {space_def.owner_key, *(m.user_key for m in space_def.members)}
    assert len(set(sachbearbeitung.member_keys) - direct) >= 2


def test_vertretung_meldewesen_stays_unchanged() -> None:
    vertretung = group("Vertretung Meldewesen")
    assert vertretung.steward_keys == ("maria",)
    assert vertretung.member_keys == ("thomas",)
    assert vertretung.library_grants == ("Interne Dienstanweisungen Meldewesen",)
    assert vertretung.space_membership == ("Meldewesen & Ausweise", "MEMBER")


def test_effective_read_matrix_matches_docs_features_demo_instance() -> None:
    """The matrix of docs/features/demo-instance.md, groups included: which fach account reads
    which library, no matter whether directly or through a group."""
    expected = {
        "Leistungen Meldewesen & Ausweise": {"maria", "selin", "andrea"},
        "Leistungen Kfz-Zulassung": {"thomas", "andrea"},
        "Satzungen & Gebührenordnungen": set(FACH_KEYS),
        "Pressemitteilungen Stadt Rheinfurt": set(FACH_KEYS),
        "Interne Dienstanweisungen Meldewesen": {"maria", "selin", "andrea", "thomas"},
        "Ratsinformationen Stadt Rheinfurt": set(FACH_KEYS),
        "Formattest auf S3": set(),
    }
    assert {lib.name: effective_readers(lib.name) for lib in DEMO.libraries} == expected


def test_space_membership_matrix() -> None:
    assert space_members("Meldewesen & Ausweise") == {"maria", "selin", "thomas"}
    assert space_members("Maria Weber – persönlich") == {"maria"}
    assert space_members("Kfz-Zulassung") == {"thomas"}
    assert space_members("Amtsleitung Bürgerbüro") == {"andrea"}
    assert space_members("Dienstbesprechung Bürgerbüro") == set(FACH_KEYS)


def test_drehbuch_frage_5_holds_in_thomas_own_space() -> None:
    """Thomas reads the internal instructions through "Vertretung Meldewesen", so the Drehbuch's
    "keine Quelle" for him rests on his own space "Kfz-Zulassung" not associating that library."""
    assert "Interne Dienstanweisungen Meldewesen" not in space("Kfz-Zulassung").library_names


def test_every_space_association_is_readable_by_its_owner() -> None:
    """associateSpaceAsset needs VIEWER on the library - directly or through a group of step 6."""
    for space_def in DEMO.spaces:
        for library_name in space_def.library_names:
            assert space_def.owner_key in effective_readers(library_name), (
                space_def.name,
                library_name,
            )


class FakeGroupApi:
    """In-memory stand-in for the group and space-member endpoints ensure_group and
    ensure_group_space_membership call; records every request that changes state."""

    def __init__(self) -> None:
        self.groups: dict[str, dict] = {}
        self.stewards: dict[str, set[str]] = {}
        self.members: dict[str, set[str]] = {}
        self.released: dict[str, bool] = {}
        self.space_members: dict[str, list[dict]] = {}
        self.writes: list[tuple[str, str]] = []

    def get_ok(self, path: str, **kwargs):
        parts = path.strip("/").split("/")
        if path == "/v1/admin/groups":
            return list(self.groups.values())
        if parts[:2] == ["v1", "groups"] and parts[3] == "stewards":
            return [{"userId": u} for u in sorted(self.stewards[parts[2]])]
        if parts[:2] == ["v1", "groups"] and parts[3] == "members":
            return [{"userId": u} for u in sorted(self.members[parts[2]])]
        if parts[:2] == ["v1", "spaces"] and parts[3] == "members":
            return list(self.space_members.setdefault(parts[2], []))
        raise AssertionError(f"unexpected GET {path}")

    def post_ok(self, path: str, expected=(200, 201, 202), json=None, **kwargs):
        self.writes.append(("POST", path))
        parts = path.strip("/").split("/")
        if path == "/v1/groups":
            group_id = f"g{len(self.groups) + 1}"
            self.groups[group_id] = {"id": group_id, "name": json["name"]}
            self.stewards[group_id] = {"u-admin"}  # createGroup appoints its caller
            self.members[group_id] = set()
            return {"id": group_id}
        if parts[:2] == ["v1", "groups"] and parts[3] == "stewards":
            self.stewards[parts[2]].add(json["userId"])
            return {}
        if parts[:2] == ["v1", "groups"] and parts[3] == "members":
            self.members[parts[2]].add(json["userId"])
            return {}
        if parts[:2] == ["v1", "spaces"] and parts[3] == "members":
            self.space_members.setdefault(parts[2], []).append(dict(json))
            return {}
        raise AssertionError(f"unexpected POST {path}")

    def put_ok(self, path: str, expected=(200,), json=None, **kwargs):
        group_id = path.strip("/").split("/")[2]
        if self.released.get(group_id) != json["releasedForUse"]:
            self.writes.append(("PUT", path))
        self.released[group_id] = json["releasedForUse"]
        return {}

    def delete_ok(self, path: str, expected=(204,), **kwargs) -> None:
        self.writes.append(("DELETE", path))
        parts = path.strip("/").split("/")
        self.stewards[parts[2]].discard(parts[4])


def seed_groups(api: FakeGroupApi) -> None:
    user_ids = {u.key: f"u-{u.key}" for u in DEMO.all_users()}
    for group_def in DEMO.groups:
        group_id = seed.ensure_group(api, user_ids, group_def)
        if group_def.space_membership:
            space_name, role = group_def.space_membership
            seed.ensure_group_space_membership(api, f"s-{space_name}", group_id, role)


def test_demo_groups_are_seeded_as_defined_and_a_second_run_changes_nothing() -> None:
    api = FakeGroupApi()
    seed_groups(api)
    by_name = {g["name"]: g["id"] for g in api.groups.values()}
    assert set(by_name) == {g.name for g in DEMO.groups}
    for group_def in DEMO.groups:
        group_id = by_name[group_def.name]
        assert api.stewards[group_id] == {f"u-{k}" for k in group_def.steward_keys}
        assert api.members[group_id] == {f"u-{k}" for k in group_def.member_keys}
        assert api.released[group_id] is group_def.released_for_use
        if group_def.space_membership:
            space_name, role = group_def.space_membership
            row = {"subjectType": "GROUP", "subjectId": group_id, "role": role}
            assert row in api.space_members[f"s-{space_name}"]

    api.writes.clear()
    seed_groups(api)
    assert api.writes == []
    assert len(api.groups) == len(DEMO.groups)
