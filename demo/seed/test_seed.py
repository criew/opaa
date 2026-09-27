"""Tests for the parts of the seed run that are checkable without a running stack (Issue #1515).

What is covered: how a failed readiness wait is reported (an unreachable stack and a rejected
token are different failures and must not share one message), and that the realm export keeps the
audience mapper the token path depends on, and how uploads map a corpus folder tree onto library
folders (against an in-memory stand-in of the document API). The seed run as a whole stays out - it
is a sequence of API calls against a live installation, covered by the nightly demo smoke run.

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
