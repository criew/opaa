#!/usr/bin/env python3
"""Shared seed mechanism for the OPAA demo/E2E data profiles (Issue #712).

Sets up a ready-to-use OPAA installation through the public API only (no direct database access,
per the issue's "Technische Hinweise"): users (provisioned by their first authenticated request),
spaces, knowledge libraries with their own source configuration (ADR-0018), VIEWER grants,
space<->library associations (#706, pure curation), upload documents and the indexing run per
library.

Two data profiles (profiles.py), one mechanism:

    python seed.py --profile demo   # Rheinfurt corpus, Keycloak login
    python seed.py --profile e2e    # minimal frozen profile, dev-auth login

Idempotent: run it twice against the same instance and the second run creates nothing new. See
each `ensure_*` function below for how each object type detects "already there".
"""

from __future__ import annotations

import argparse
import mimetypes
import sys
import time
from pathlib import Path

import requests

from api_client import ApiError, Client
from auth import AuthError, DevHeaderAuth, KeycloakPasswordAuth
from profiles import PROFILES, LibraryDef, Profile, SpaceDef, UserDef

INDEXING_POLL_INTERVAL_SECONDS = 3
# Transient errors expected right after `docker compose ... up`: the backend/Keycloak container
# exists but is not yet accepting connections, or (dev-auth) is still applying Liquibase.
TRANSIENT_STARTUP_ERRORS = (ApiError, AuthError, requests.exceptions.ConnectionError)


def build_client(
    base_url: str,
    user: UserDef,
    profile: Profile,
    keycloak_url: str,
    realm: str,
    seed_client_id: str,
    rate_limit_wait_seconds: int,
) -> Client:
    if profile.auth_mode == "keycloak":
        auth = KeycloakPasswordAuth(
            keycloak_url=keycloak_url,
            realm=realm,
            client_id=seed_client_id,
            username=user.identity,
            password=user.password,
        )
    elif profile.auth_mode == "dev":
        auth = DevHeaderAuth(subject=user.identity)
    else:
        raise ValueError(f"Unbekannter auth_mode '{profile.auth_mode}'")
    return Client(
        base_url=base_url, auth=auth, rate_limit_wait_seconds=rate_limit_wait_seconds, label=user.key
    )


def wait_until_ready(admin_client: Client, timeout_seconds: int = 90) -> None:
    """Waits for the backend (and, for the 'demo' profile, Keycloak's token endpoint via
    admin_client's own auth provider) to accept requests. This is the normal case right after
    `docker compose ... up`: neither the backend container nor keycloak has an explicit
    `depends_on`/healthcheck gate on this script, so the very first request can easily race the
    container's own startup."""
    deadline = time.monotonic() + timeout_seconds
    last_error: Exception | None = None
    while time.monotonic() < deadline:
        try:
            admin_client.get_ok("/v1/auth/me")
            return
        except TRANSIENT_STARTUP_ERRORS as error:
            last_error = error
            time.sleep(3)
    raise SystemExit(
        f"Backend bzw. Keycloak nach {timeout_seconds}s nicht erreichbar (letzter Fehler: "
        f"{last_error}). Läuft der Stack bereits vollständig (docker compose ... up, ggf. "
        "--profile demo)?"
    )


def provision_users(clients: dict[str, Client], profile: Profile) -> dict[str, str]:
    """Triggers UserProvisioningFilter for every user by making one authenticated request each,
    and returns each user's database id, keyed by the profile's own user key."""
    user_ids: dict[str, str] = {}
    for user in profile.all_users():
        info = clients[user.key].get_ok("/v1/auth/me")
        user_ids[user.key] = info["id"]
        print(f"  Nutzer bereitgestellt: {user.display_name} ({info['systemRole']}, {info['id']})")
    admin_role = clients[profile.admin.key].get_ok("/v1/auth/me")["systemRole"]
    if admin_role != "SYSTEM_ADMIN":
        raise SystemExit(
            f"Admin-Konto '{profile.admin.identity}' hat nicht die Rolle SYSTEM_ADMIN "
            f"(tatsächlich: {admin_role}). UserService#findOrCreateUser vergibt SYSTEM_ADMIN nur "
            "beim allerersten Anlegen der Nutzerzeile, anhand von OPAA_INITIAL_ADMIN_EMAIL zu "
            "diesem Zeitpunkt - eine nachträglich geänderte Variable hebt eine bereits bestehende "
            "Nutzerzeile nicht mehr an. Abhilfe: die Nutzerzeile aus der Datenbank entfernen oder "
            "den Stack mit 'docker compose ... down -v' zurücksetzen und den Seed erneut laufen "
            f"lassen - diesmal mit OPAA_INITIAL_ADMIN_EMAIL={profile.admin.email} bereits beim "
            "allerersten Start gesetzt."
        )
    return user_ids


def ensure_space(
    admin_client: Client,
    clients: dict[str, Client],
    user_ids: dict[str, str],
    space_def: SpaceDef,
) -> str:
    """Idempotency: a space is only visible via GET /v1/spaces to its own members, and the admin
    is deliberately not a member of every demo space (e.g. Maria's personal space must have no
    other member at all). So existence is checked through the *owner's own* session instead of the
    admin's - the owner is always a member of a space they own."""
    owner_client = clients[space_def.owner_key]
    existing = owner_client.get_ok("/v1/spaces")
    for space in existing:
        if space["name"] == space_def.name:
            print(f"  Space bereits vorhanden: {space_def.name}")
            return space["id"]

    body = {
        "name": space_def.name,
        "description": space_def.description,
        "visibility": "PRIVATE",
        "ownerId": user_ids[space_def.owner_key],
        "initialMembers": [
            {"userId": user_ids[member.user_key], "role": member.role}
            for member in space_def.members
        ],
    }
    created = admin_client.post_ok("/v1/spaces", json=body, expected=(201,))
    print(f"  Space angelegt: {space_def.name} ({created['id']})")
    return created["id"]


def ensure_library(admin_client: Client, library_def: LibraryDef) -> str:
    """Idempotency: every demo/e2e library is owned by the admin account, so listLibraries as the
    admin always includes it - a straightforward name lookup."""
    existing = admin_client.get_ok("/v1/libraries")
    for library in existing:
        if library["name"] == library_def.name:
            print(f"  Bibliothek bereits vorhanden: {library_def.name}")
            return library["id"]

    body = {
        "name": library_def.name,
        "description": library_def.description,
        "sourceType": library_def.source_type,
        # PRIVATE, not ORGANIZATION: docs/features/spaces-and-assets.md's read expression grants
        # ORGANIZATION-visible libraries to every user in the organization regardless of any grant
        # - that would silently defeat the demo's own VIEWER matrix (Thomas must not read the
        # internal Meldewesen instructions). "listed" still surfaces the library in the catalog for
        # everyone (discoverable-without-access, same doc section) without granting read access.
        "visibility": "PRIVATE",
        "listed": True,
    }
    if library_def.source_url:
        body["sourceUrl"] = library_def.source_url
    created = admin_client.post_ok("/v1/libraries", json=body, expected=(201,))
    print(f"  Bibliothek angelegt: {library_def.name} ({created['id']})")
    return created["id"]


def ensure_association(owner_client: Client, space_id: str, library_id: str) -> None:
    # associateSpaceLibrary is idempotent by design (see opaa-api.yaml): an already-associated
    # library returns its existing association unchanged, also with 201.
    owner_client.post_ok(
        f"/v1/spaces/{space_id}/libraries",
        json={"libraryId": library_id},
        expected=(201,),
    )


def ensure_grant(admin_client: Client, library_id: str, user_id: str, role: str = "VIEWER") -> None:
    # upsertAssetGrant is idempotent per subject by design (see opaa-api.yaml) - always safe to call.
    admin_client.post_ok(
        f"/v1/libraries/{library_id}/grants",
        json={"subjectType": "USER", "subjectId": user_id, "role": role},
        expected=(200,),
    )


def existing_documents_by_name(admin_client: Client, library_id: str) -> dict[str, dict]:
    by_name: dict[str, dict] = {}
    page = 0
    while True:
        result = admin_client.get_ok(
            f"/v1/libraries/{library_id}/documents", params={"page": page, "size": 100}
        )
        for item in result["items"]:
            by_name[item["fileName"]] = item
        if (page + 1) * result["size"] >= result["totalElements"] or not result["items"]:
            break
        page += 1
    return by_name


def upload_documents(admin_client: Client, library_id: str, upload_dir: Path) -> None:
    """Uploads every file in upload_dir not already present with status PENDING/INDEXED. A
    document whose previous attempt ended FAILED is re-uploaded rather than skipped - "already
    there" only means so for a document that actually succeeded or is still being processed."""
    existing = existing_documents_by_name(admin_client, library_id)
    for file_path in sorted(p for p in upload_dir.iterdir() if p.is_file()):
        current = existing.get(file_path.name)
        if current is not None and current["status"] != "FAILED":
            print(f"    bereits hochgeladen: {file_path.name} ({current['status']})")
            continue
        if current is not None:
            print(
                f"    erneuter Versuch nach FAILED: {file_path.name} "
                f"({current.get('errorMessage')})"
            )
        content_type = mimetypes.guess_type(file_path.name)[0] or "application/octet-stream"
        with file_path.open("rb") as handle:
            admin_client.post_ok(
                f"/v1/libraries/{library_id}/documents",
                files={"file": (file_path.name, handle, content_type)},
                expected=(201,),
            )
        print(f"    hochgeladen: {file_path.name}")


def wait_for_uploads_indexed(
    admin_client: Client, library_id: str, name: str, timeout_seconds: int
) -> None:
    """Upload returns 201 while the document row is still PENDING (#434, ADR-0018) - Tika parsing
    and embedding run afterwards, asynchronously, through the same executor infrastructure the
    connector indexing paths use. Without waiting here, the seed would report success before a
    single upload document is actually searchable, and a FAILED one would go unnoticed."""
    deadline = time.monotonic() + timeout_seconds
    documents: list[dict] = []
    while True:
        documents = list(existing_documents_by_name(admin_client, library_id).values())
        pending = [d for d in documents if d["status"] == "PENDING"]
        if not pending:
            break
        if time.monotonic() > deadline:
            raise SystemExit(
                f"Upload-Indizierung für '{name}' hat das Zeitlimit von {timeout_seconds}s "
                f"überschritten ({len(pending)} Dokument(e) noch PENDING)."
            )
        time.sleep(INDEXING_POLL_INTERVAL_SECONDS)

    failed = [d for d in documents if d["status"] == "FAILED"]
    if failed:
        details = "; ".join(f"{d['fileName']}: {d.get('errorMessage')}" for d in failed)
        raise SystemExit(f"Upload-Indizierung für '{name}' fehlgeschlagen: {details}")

    indexed = sum(1 for d in documents if d["status"] == "INDEXED")
    print(f"  Uploads für '{name}' indiziert: {indexed} Dokument(e)")


def trigger_indexing(admin_client: Client, library_id: str, name: str, timeout_seconds: int) -> None:
    response = admin_client.post(f"/v1/libraries/{library_id}/indexing")
    if response.status_code == 409:
        # A run is already in progress (possibly from a previous, interrupted seed attempt) - just
        # poll the existing one instead of failing.
        print(f"  Indizierung für '{name}' läuft bereits, warte auf Abschluss …")
    elif response.status_code != 202:
        raise ApiError(response)
    else:
        print(f"  Indizierung für '{name}' gestartet …")

    deadline = time.monotonic() + timeout_seconds
    while True:
        status = admin_client.get_ok(f"/v1/libraries/{library_id}/indexing/status")
        if status["status"] in ("COMPLETED", "FAILED"):
            break
        if time.monotonic() > deadline:
            raise SystemExit(
                f"Indizierung für '{name}' hat das Zeitlimit von {timeout_seconds}s überschritten "
                f"(letzter Status: {status['status']})"
            )
        time.sleep(INDEXING_POLL_INTERVAL_SECONDS)

    if status["status"] != "COMPLETED" or status["documentsFailed"] > 0:
        raise SystemExit(
            f"Indizierung für '{name}' nicht sauber abgeschlossen: status={status['status']}, "
            f"documentsFailed={status['documentsFailed']}, message={status.get('message')}"
        )
    print(
        f"  Indizierung für '{name}' abgeschlossen: "
        f"{status['documentsIndexedTotal']} Dokumente, {status['documentsSkipped']} übersprungen"
    )


def run(args: argparse.Namespace) -> None:
    profile = PROFILES[args.profile]
    print(f"Seed-Profil: {profile.name} (Auth: {profile.auth_mode})")

    clients = {
        user.key: build_client(
            base_url=args.base_url,
            user=user,
            profile=profile,
            keycloak_url=args.keycloak_url,
            realm=args.realm,
            seed_client_id=args.seed_client_id,
            rate_limit_wait_seconds=args.rate_limit_wait_seconds,
        )
        for user in profile.all_users()
    }
    admin_client = clients[profile.admin.key]

    print("Warte auf Backend/Keycloak …")
    wait_until_ready(admin_client)

    print("1/6 Nutzer bereitstellen (erste authentifizierte Anfrage je Nutzer) …")
    user_ids = provision_users(clients, profile)

    print("2/6 Spaces einrichten …")
    space_ids: dict[str, str] = {}
    for space_def in profile.spaces:
        space_ids[space_def.name] = ensure_space(admin_client, clients, user_ids, space_def)

    print("3/6 Wissensbibliotheken einrichten …")
    library_ids: dict[str, str] = {}
    for library_def in profile.libraries:
        library_ids[library_def.name] = ensure_library(admin_client, library_def)

    print("4/6 Leserechte (VIEWER) und Upload-Dokumente …")
    for library_def in profile.libraries:
        library_id = library_ids[library_def.name]
        for viewer_key in library_def.viewer_keys:
            ensure_grant(admin_client, library_id, user_ids[viewer_key])
        if library_def.source_type == "UPLOAD":
            if library_def.upload_dir is None or not library_def.upload_dir.is_dir():
                raise SystemExit(
                    f"Upload-Verzeichnis für '{library_def.name}' fehlt: {library_def.upload_dir}"
                )
            print(f"  Uploads für '{library_def.name}':")
            upload_documents(admin_client, library_id, library_def.upload_dir)
            wait_for_uploads_indexed(
                admin_client,
                library_id,
                library_def.name,
                timeout_seconds=args.indexing_timeout_seconds,
            )

    print("5/6 Space↔Bibliothek-Zuordnungen (Assoziation als Kuratierung, #706) …")
    for space_def in profile.spaces:
        for library_name in space_def.library_names:
            if library_name not in library_ids:
                raise SystemExit(
                    f"Space '{space_def.name}' referenziert eine unbekannte Bibliothek "
                    f"'{library_name}' - library_names muss auf eine LibraryDef des Profils zeigen."
                )
            # After step 4 the owner holds VIEWER on the library (grants) and is CURATOR or above
            # on their own space - exactly what associateSpaceLibrary requires.
            ensure_association(
                clients[space_def.owner_key],
                space_ids[space_def.name],
                library_ids[library_name],
            )
            print(f"  zugeordnet: {space_def.name} ← {library_name}")

    print("6/6 Indizierung je Bibliothek auslösen (ADR-0018) …")
    for library_def in profile.libraries:
        if library_def.source_type == "UPLOAD":
            # UPLOAD has no run of its own (ADR-0018) - indexing happens per document on upload.
            continue
        trigger_indexing(
            admin_client,
            library_ids[library_def.name],
            library_def.name,
            timeout_seconds=args.indexing_timeout_seconds,
        )

    print(f"Seed-Profil '{profile.name}' abgeschlossen.")


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=sorted(PROFILES), required=True)
    parser.add_argument(
        "--base-url",
        default="http://localhost:8081/api",
        help="OPAA backend API base URL (default: %(default)s)",
    )
    parser.add_argument(
        "--keycloak-url",
        default="http://localhost:8180",
        help="Keycloak base URL, only used for the 'demo' profile (default: %(default)s)",
    )
    parser.add_argument("--realm", default="opaa")
    parser.add_argument("--seed-client-id", default="opaa-seed")
    parser.add_argument(
        "--rate-limit-wait-seconds",
        type=int,
        default=65,
        help="Wait time on HTTP 429 before retrying (default: %(default)s, "
        "> opaa.rate-limit.indexing.window-seconds default of 60)",
    )
    parser.add_argument("--indexing-timeout-seconds", type=int, default=300)
    return parser.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)
    try:
        run(args)
    except ApiError as error:
        print(f"API-Fehler: {error}", file=sys.stderr)
        return 1
    except AuthError as error:
        print(f"Anmeldefehler: {error}", file=sys.stderr)
        return 1
    except requests.exceptions.ConnectionError as error:
        print(f"Verbindungsfehler: {error}", file=sys.stderr)
        return 1
    except SystemExit as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
