"""Tests for the parts of the seed run that are checkable without a running stack (Issue #1515).

What is covered: how a failed readiness wait is reported (an unreachable stack and a rejected
token are different failures and must not share one message), and that the realm export keeps the
audience mapper the token path depends on, and how uploads map a corpus folder tree onto library
folders (against an in-memory stand-in of the document API). The demo profile's groups and the
effective permission matrix they produce are checked as data, and the group steps run twice against
an in-memory API to show the second run writes nothing. The seed run as a whole stays out - it is a
sequence of API calls against a live installation, covered by the nightly demo smoke run.

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
from profiles import PROFILES  # noqa: E402

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


class FolderAwareLibrary:
    """In-memory stand-in for one UPLOAD library behind api_client.Client: GET .../documents lists
    exactly one folder level (folderId omitted = root) with its direct subfolders, POST creates the
    folderPath chain idempotently below the root - the contract of libraries.yaml."""

    def __init__(self, page_size: int = 2) -> None:
        self.page_size = page_size
        self.folders: dict[str, tuple[str | None, str]] = {}  # id -> (parent id, name)
        self.documents: list[dict] = []
        self.uploads: list[tuple[str, str | None]] = []  # (fileName, folderPath) per POST

    def _folder(self, parent: str | None, name: str) -> str:
        for folder_id, (existing_parent, existing_name) in self.folders.items():
            if existing_parent == parent and existing_name == name:
                return folder_id
        folder_id = f"folder-{len(self.folders) + 1}"
        self.folders[folder_id] = (parent, name)
        return folder_id

    def get_ok(self, path: str, params: dict | None = None, **kwargs) -> dict:
        params = params or {}
        folder_id = params.get("folderId")
        page = params.get("page", 0)
        size = min(params.get("size", 20), self.page_size)
        in_folder = sorted(
            (d for d in self.documents if d["folderId"] == folder_id), key=lambda d: d["fileName"]
        )
        return {
            "items": in_folder[page * size : (page + 1) * size],
            "page": page,
            "size": size,
            "totalElements": len(in_folder),
            "folders": [
                {"id": fid, "name": name, "documentCount": 0}
                for fid, (parent, name) in self.folders.items()
                if parent == folder_id
            ],
            "breadcrumb": [],
        }

    def post_ok(self, path: str, expected=(201,), files=None, data=None, **kwargs) -> dict:
        file_name = files["file"][0]
        folder_path = (data or {}).get("folderPath")
        self.uploads.append((file_name, folder_path))
        folder_id = None
        for segment in (folder_path or "").split("/"):
            if segment:
                folder_id = self._folder(folder_id, segment)
        document = {"fileName": file_name, "folderId": folder_id, "status": "INDEXED"}
        self.documents.append(document)
        return document


def write_tree(root: Path, relative_paths: list[str]) -> None:
    for relative in relative_paths:
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(relative, encoding="utf-8")


def test_upload_sends_the_folder_path_relative_to_the_library_root(tmp_path: Path) -> None:
    write_tree(tmp_path, ["wurzel.txt", "01 Melderecht/01 Ummeldung/a.txt", "04 Gebühren/b.txt"])
    library = FolderAwareLibrary()

    seed.upload_documents(library, "lib", tmp_path)

    assert sorted(library.uploads) == [
        ("a.txt", "01 Melderecht/01 Ummeldung"),
        ("b.txt", "04 Gebühren"),
        ("wurzel.txt", None),
    ]


def test_second_upload_run_uploads_nothing(tmp_path: Path) -> None:
    write_tree(
        tmp_path,
        ["wurzel.txt", "01 Melderecht/01 Ummeldung/a.txt", "01 Melderecht/02 Auskunft/b.txt",
         "01 Melderecht/02 Auskunft/c.txt", "01 Melderecht/02 Auskunft/d.txt"],
    )
    library = FolderAwareLibrary(page_size=2)
    seed.upload_documents(library, "lib", tmp_path)
    first_run = len(library.uploads)

    seed.upload_documents(library, "lib", tmp_path)

    assert first_run == 5
    assert len(library.uploads) == first_run


def test_same_file_name_in_another_folder_is_a_different_document(tmp_path: Path) -> None:
    write_tree(tmp_path, ["01 Melderecht/hinweise.txt", "02 Ausweise/hinweise.txt"])
    library = FolderAwareLibrary()
    library.post_ok("", files={"file": ("hinweise.txt", None)}, data={"folderPath": "01 Melderecht"})
    library.uploads.clear()

    seed.upload_documents(library, "lib", tmp_path)

    assert library.uploads == [("hinweise.txt", "02 Ausweise")]


def test_flat_library_stops_the_seed_before_any_upload(tmp_path: Path) -> None:
    """A library that still holds the corpus flat in its root cannot be re-sorted: the API answers
    the same content with 409, after it has already created the folder chain. The seed must stop
    before the first upload, with a message that says what to do."""
    write_tree(tmp_path, ["01 Melderecht/a.txt", "02 Ausweise/b.txt"])
    library = FolderAwareLibrary()
    library.post_ok("", files={"file": ("a.txt", None)}, data=None)
    library.uploads.clear()

    with pytest.raises(SystemExit) as exit_info:
        seed.upload_documents(library, "lib", tmp_path)

    assert library.uploads == []
    assert library.folders == {}
    assert "flach" in str(exit_info.value)
    assert "neu aufsetzen" in str(exit_info.value)
    assert "a.txt" in str(exit_info.value)


def test_conflict_on_upload_ends_with_a_german_message(tmp_path: Path) -> None:
    write_tree(tmp_path, ["01 Melderecht/a.txt"])

    class ConflictingLibrary(FolderAwareLibrary):
        def post_ok(self, path: str, expected=(201,), files=None, data=None, **kwargs) -> dict:
            raise ApiError(response(409, body="Diese Datei ist bereits in dieser Bibliothek vorhanden"))

    with pytest.raises(SystemExit) as exit_info:
        seed.upload_documents(ConflictingLibrary(), "lib", tmp_path)

    message = str(exit_info.value)
    assert "01 Melderecht/a.txt" in message
    assert "bereits" in message
    assert "neu aufsetzen" in message


def test_existing_documents_are_found_in_every_folder_level() -> None:
    library = FolderAwareLibrary(page_size=1)
    for name, folder_path in [("r.txt", None), ("a.txt", "x/y"), ("b.txt", "x/y"), ("c.txt", "x")]:
        library.post_ok("", files={"file": (name, None)}, data={"folderPath": folder_path})

    found = seed.existing_documents(library, "lib")

    assert set(found) == {("", "r.txt"), ("x/y", "a.txt"), ("x/y", "b.txt"), ("x", "c.txt")}


def test_demo_upload_library_has_at_least_two_folder_levels() -> None:
    """The library "Interne Dienstanweisungen Meldewesen" is the demo's showcase for folders and
    their breadcrumb: at least one document must lie two folder levels below the root."""
    library = next(
        lib
        for lib in PROFILES["demo"].libraries
        if lib.name == "Interne Dienstanweisungen Meldewesen"
    )
    depths = {
        len(path.relative_to(library.upload_dir).parts) - 1
        for path in library.upload_dir.rglob("*")
        if path.is_file()
    }
    assert max(depths) >= 2
    assert 0 not in depths


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


# --- Prompt libraries of the demo profile ------------------------------------------------------
# The rules below mirror io.opaa.prompt.PromptTemplate and the PromptRequest schema of
# opaa-api/src/main/openapi/prompts.yaml, so a profile the backend would refuse fails here already.

import datetime  # noqa: E402
import re  # noqa: E402

PROMPT_NAME = re.compile(r"^[a-z0-9]+(-[a-z0-9]+)*$")
VARIABLE_NAME = re.compile(r"^[A-Za-z][A-Za-z0-9_]*$")
PLACEHOLDER = re.compile(r"\{\{(.*?)}}", re.DOTALL)
SYSTEM_VARIABLES = {"CURRENT_DATE", "USER_NAME"}
VARIABLE_TYPES = {"TEXT", "TEXTAREA", "SELECT", "DATE"}
AMTSLEITUNG_LIBRARY = "Vorlagen Amtsleitung"
BUERGERBUERO_LIBRARY = "Textbausteine Bürgerbüro"


def demo_prompt_library(name: str):
    return next(lib for lib in profiles.DEMO_PROFILE.prompt_libraries if lib.name == name)


def all_demo_prompts():
    return [
        (library, prompt)
        for library in profiles.DEMO_PROFILE.prompt_libraries
        for prompt in library.prompts
    ]


def placeholders(text: str) -> set[str]:
    names = set()
    for raw in PLACEHOLDER.findall(text):
        name = raw.strip()
        assert VARIABLE_NAME.match(name) and len(name) <= 64, f"ungültiger Platzhalter {{{{{raw}}}}}"
        names.add(name.upper() if name.upper() in SYSTEM_VARIABLES else name)
    return names


def test_demo_profile_seeds_both_prompt_libraries() -> None:
    names = {lib.name for lib in profiles.DEMO_PROFILE.prompt_libraries}
    assert {BUERGERBUERO_LIBRARY, AMTSLEITUNG_LIBRARY} <= names


def test_e2e_profile_stays_without_prompt_libraries() -> None:
    assert profiles.E2E_PROFILE.prompt_libraries == ()


def test_buergerbuero_textbausteine_reach_all_accounts_and_are_listed() -> None:
    library = demo_prompt_library(BUERGERBUERO_LIBRARY)
    assert library.all_accounts_viewer
    assert library.listed
    prompt_names = {prompt.name for prompt in library.prompts}
    assert {
        "antwort-buergeranfrage",
        "gebuehrenauskunft-personalausweis",
        "aktenvermerk",
        "pressemitteilung-ratsbeschluss",
    } <= prompt_names


def test_amtsleitung_templates_reach_only_andrea() -> None:
    library = demo_prompt_library(AMTSLEITUNG_LIBRARY)
    assert library.owner_key == "andrea"
    assert not library.all_accounts_viewer
    assert library.viewer_keys == ()
    # Unlisted: a listed library would appear in the catalog of every account, if only as an
    # entry without access.
    assert not library.listed
    assert library.space_names == ("Amtsleitung Bürgerbüro",)
    assert {"wochenbericht-dezernentin", "stellungnahme-hauptausschuss"} <= {
        prompt.name for prompt in library.prompts
    }


def test_every_demo_prompt_library_offers_a_variable_form() -> None:
    for library in profiles.DEMO_PROFILE.prompt_libraries:
        assert any(prompt.variables for prompt in library.prompts), library.name


def test_every_demo_prompt_satisfies_the_backend_template_rules() -> None:
    for library, prompt in all_demo_prompts():
        where = f"{library.name}/{prompt.name}"
        assert PROMPT_NAME.match(prompt.name) and len(prompt.name) <= 64, where
        assert 1 <= len(prompt.title) <= 255, where
        assert prompt.description is None or len(prompt.description) <= 2000, where
        assert 1 <= len(prompt.text) <= 8000, where
        assert len(prompt.variables) <= 20, where

        defined = [variable.name for variable in prompt.variables]
        assert len(defined) == len(set(defined)), f"{where}: Variable doppelt definiert"
        used = placeholders(prompt.text)
        assert used - SYSTEM_VARIABLES == set(defined), (
            f"{where}: Platzhalter {sorted(used - SYSTEM_VARIABLES)} ≠ Definitionen {sorted(defined)}"
        )
        for variable in prompt.variables:
            assert VARIABLE_NAME.match(variable.name) and len(variable.name) <= 64, where
            assert variable.name.upper() not in SYSTEM_VARIABLES, where
            assert 1 <= len(variable.label.strip()) <= 255, where
            assert variable.type in VARIABLE_TYPES, where
            if variable.type == "SELECT":
                assert 1 <= len(variable.options) <= 50, where
                assert len(set(variable.options)) == len(variable.options), where
                assert all(0 < len(option.strip()) <= 255 for option in variable.options), where
            else:
                assert variable.options == (), where
            if variable.default_value is not None:
                assert len(variable.default_value) <= 2000, where
                if variable.type == "SELECT":
                    assert variable.default_value in variable.options, where
                if variable.type == "DATE":
                    datetime.date.fromisoformat(variable.default_value)


def test_prompt_names_are_unique_within_each_library() -> None:
    for library in profiles.DEMO_PROFILE.prompt_libraries:
        names = [prompt.name for prompt in library.prompts]
        assert len(names) == len(set(names)), library.name


def test_prompt_library_spaces_exist_and_their_owner_can_read_the_library() -> None:
    """associateSpaceAsset needs CURATOR on the space plus VIEWER on the asset; the seed associates
    through the space owner's session, so that owner has to be able to read the library."""
    spaces = {space.name: space for space in profiles.DEMO_PROFILE.spaces}
    for library in profiles.DEMO_PROFILE.prompt_libraries:
        for space_name in library.space_names:
            assert space_name in spaces, f"{library.name}: unbekannter Space {space_name}"
            owner = spaces[space_name].owner_key
            assert (
                library.all_accounts_viewer
                or owner == library.owner_key
                or owner in library.viewer_keys
            ), f"{library.name}: {owner} kann die Bibliothek nicht lesen"


class FakePromptApi:
    """In-memory stand-in for the prompt-library and asset endpoints the seed calls, with the
    reading formula of the backend: owner, direct grant or a grant to ALL_ACCOUNTS."""

    def __init__(self) -> None:
        self.libraries: dict[str, dict] = {}
        self.prompts: dict[str, list[dict]] = {}
        self.grants: dict[str, dict[tuple[str, str | None], str]] = {}
        self.associations: set[tuple[str, str]] = set()
        self.calls: list[tuple[str, str, dict | None]] = []
        self._next_id = 0

    def new_id(self) -> str:
        self._next_id += 1
        return f"00000000-0000-0000-0000-{self._next_id:012d}"

    def can_read(self, user_id: str, library_id: str) -> bool:
        grants = self.grants[library_id]
        return ("ALL_ACCOUNTS", None) in grants or ("USER", user_id) in grants

    def client(self, user_id: str) -> "FakePromptClient":
        return FakePromptClient(self, user_id)


class FakePromptClient:
    def __init__(self, api: FakePromptApi, user_id: str) -> None:
        self.api = api
        self.user_id = user_id

    def get_ok(self, path: str, **kwargs):
        self.api.calls.append(("GET", path, None))
        if path == "/v1/prompt-libraries":
            return [
                lib
                for lib_id, lib in self.api.libraries.items()
                if self.api.can_read(self.user_id, lib_id)
            ]
        match = re.fullmatch(r"/v1/prompt-libraries/([^/]+)/prompts", path)
        if match:
            assert self.api.can_read(self.user_id, match.group(1)), "403"
            return list(self.api.prompts[match.group(1)])
        raise AssertionError(f"unerwarteter GET {path}")

    def post_ok(self, path: str, expected=(200, 201, 202), json=None, **kwargs):
        self.api.calls.append(("POST", path, json))
        if path == "/v1/prompt-libraries":
            assert 201 in expected
            lib_id = self.api.new_id()
            library = {
                "id": lib_id,
                "name": json["name"],
                "description": json.get("description"),
                "ownerType": "USER",
                "ownerId": self.user_id,
                "listed": bool(json.get("listed")),
            }
            self.api.libraries[lib_id] = library
            self.api.prompts[lib_id] = []
            self.api.grants[lib_id] = {("USER", self.user_id): "OWNER"}
            return library
        match = re.fullmatch(r"/v1/prompt-libraries/([^/]+)/prompts", path)
        if match:
            assert 201 in expected
            lib_id = match.group(1)
            assert not any(p["name"] == json["name"] for p in self.api.prompts[lib_id]), "409"
            prompt = {"id": self.api.new_id(), "promptLibraryId": lib_id, **json}
            self.api.prompts[lib_id].append(prompt)
            return prompt
        match = re.fullmatch(r"/v1/assets/PROMPT_LIBRARY/([^/]+)/grants", path)
        if match:
            assert 200 in expected
            lib_id = match.group(1)
            assert self.api.grants[lib_id].get(("USER", self.user_id)) in ("OWNER", "MANAGER")
            if json["subjectType"] == "ALL_ACCOUNTS":
                assert "subjectId" not in json, "ALL_ACCOUNTS nennt keine subjectId (400)"
            self.api.grants[lib_id][(json["subjectType"], json.get("subjectId"))] = json["role"]
            return {"id": self.api.new_id(), **json}
        match = re.fullmatch(r"/v1/spaces/([^/]+)/assets", path)
        if match:
            assert 201 in expected
            assert json["assetType"] == "PROMPT_LIBRARY"
            assert self.api.can_read(self.user_id, json["assetId"]), "403: kein VIEWER"
            self.api.associations.add((match.group(1), json["assetId"]))
            return {"spaceId": match.group(1), **json}
        raise AssertionError(f"unerwarteter POST {path}")


def seed_prompt_libraries_into(api: FakePromptApi) -> tuple[dict[str, str], dict[str, str]]:
    profile = profiles.DEMO_PROFILE
    user_ids = {user.key: f"user-{user.key}" for user in profile.all_users()}
    space_ids = {space.name: f"space-{index}" for index, space in enumerate(profile.spaces)}
    clients = {key: api.client(user_id) for key, user_id in user_ids.items()}
    seed.seed_prompt_libraries(clients, user_ids, space_ids, profile)
    return user_ids, space_ids


def creating_calls(api: FakePromptApi) -> list[tuple[str, str, dict | None]]:
    return [
        call
        for call in api.calls
        if call[0] == "POST" and re.fullmatch(r"/v1/prompt-libraries(/[^/]+/prompts)?", call[1])
    ]


def test_seed_creates_prompt_libraries_with_prompts_grants_and_associations() -> None:
    api = FakePromptApi()
    user_ids, space_ids = seed_prompt_libraries_into(api)

    by_name = {lib["name"]: lib for lib in api.libraries.values()}
    for library_def in profiles.DEMO_PROFILE.prompt_libraries:
        library = by_name[library_def.name]
        assert library["ownerId"] == user_ids[library_def.owner_key]
        assert library["listed"] == library_def.listed
        assert [p["name"] for p in api.prompts[library["id"]]] == [
            p.name for p in library_def.prompts
        ]
        for space_name in library_def.space_names:
            assert (space_ids[space_name], library["id"]) in api.associations

    textbausteine = by_name[BUERGERBUERO_LIBRARY]["id"]
    assert api.grants[textbausteine][("ALL_ACCOUNTS", None)] == "VIEWER"


def test_seed_sends_prompts_in_the_shape_of_prompt_request() -> None:
    api = FakePromptApi()
    seed_prompt_libraries_into(api)
    prompt_bodies = [
        body for method, path, body in api.calls if method == "POST" and path.endswith("/prompts")
    ]
    assert prompt_bodies
    allowed = {"name", "title", "description", "text", "variables", "sortOrder"}
    variable_keys = {"name", "label", "type", "required", "defaultValue", "options"}
    for body in prompt_bodies:
        assert set(body) <= allowed
        assert {"name", "title", "text"} <= set(body)
        for variable in body.get("variables", []):
            assert {"name", "label", "type", "required"} <= set(variable) <= variable_keys
            assert isinstance(variable["required"], bool)
            if variable["type"] != "SELECT":
                assert "options" not in variable


def test_amtsleitung_templates_are_readable_by_andrea_alone_after_seeding() -> None:
    api = FakePromptApi()
    user_ids, _ = seed_prompt_libraries_into(api)
    amtsleitung = next(lib for lib in api.libraries.values() if lib["name"] == AMTSLEITUNG_LIBRARY)
    textbausteine = next(
        lib for lib in api.libraries.values() if lib["name"] == BUERGERBUERO_LIBRARY
    )
    assert api.can_read(user_ids["andrea"], amtsleitung["id"])
    for key in ("maria", "selin", "thomas"):
        assert not api.can_read(user_ids[key], amtsleitung["id"]), key
        assert api.can_read(user_ids[key], textbausteine["id"]), key


def test_seeding_prompt_libraries_twice_creates_nothing_new() -> None:
    api = FakePromptApi()
    seed_prompt_libraries_into(api)
    first_libraries = dict(api.libraries)
    first_prompt_count = sum(len(prompts) for prompts in api.prompts.values())
    created_first = len(creating_calls(api))

    seed_prompt_libraries_into(api)

    assert api.libraries == first_libraries
    assert sum(len(prompts) for prompts in api.prompts.values()) == first_prompt_count
    assert len(creating_calls(api)) == created_first


def test_same_named_prompt_library_of_another_owner_is_not_taken_over() -> None:
    """A readable library of the same name owned by someone else (e.g. shared with "Alle Konten")
    must not stand in for the profile's own - the seed would otherwise fill a foreign library."""
    api = FakePromptApi()
    foreign = api.client("user-maria").post_ok(
        "/v1/prompt-libraries",
        json={"name": AMTSLEITUNG_LIBRARY, "listed": False},
        expected=(201,),
    )
    api.client("user-maria").post_ok(
        f"/v1/assets/PROMPT_LIBRARY/{foreign['id']}/grants",
        json={"subjectType": "ALL_ACCOUNTS", "role": "VIEWER"},
        expected=(200,),
    )
    user_ids, _ = seed_prompt_libraries_into(api)
    owned = [
        lib
        for lib in api.libraries.values()
        if lib["name"] == AMTSLEITUNG_LIBRARY and lib["ownerId"] == user_ids["andrea"]
    ]
    assert len(owned) == 1
    assert api.prompts[foreign["id"]] == []
