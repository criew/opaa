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
import os
import sys
import time
from pathlib import Path

import requests

from api_client import ApiError, Client
from auth import AuthError, DevHeaderAuth, KeycloakPasswordAuth, LocalPasswordAuth
from profiles import PROFILES, GroupDef, LibraryDef, Profile, SpaceDef, UserDef

INDEXING_POLL_INTERVAL_SECONDS = 3
# Transient errors expected right after `docker compose ... up`: the backend/Keycloak container
# exists but is not yet accepting connections, answers slowly enough to run into the request
# timeout, or (dev-auth) is still applying Liquibase.
TRANSIENT_STARTUP_ERRORS = (
    ApiError,
    AuthError,
    requests.exceptions.ConnectionError,
    requests.exceptions.Timeout,
)
TOKEN_REJECTED_STATUS_CODES = (401, 403)


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


def wait_until_ready(admin_client: Client, auth_mode: str, timeout_seconds: int = 90) -> None:
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
    raise SystemExit(_readiness_failure_message(last_error, timeout_seconds, auth_mode))


# Why a rejected request was rejected, per auth mode - both modes answer with 401, and a cause from
# the wrong mode is exactly the misleading message this diagnosis exists to avoid.
REJECTION_HINTS = {
    "keycloak": (
        "Der Tokenerwerb an Keycloak war erfolgreich, abgelehnt wird erst der API-Aufruf. "
        "Häufigste Ursache: Das Token des Clients 'opaa-seed' nennt die client_id der "
        "Anbieterzeile weder in 'azp' noch in 'aud'. Dann fehlt 'opaa-seed' der Audience-Mapper "
        "aus keycloak/realm-export.json oder er zeigt auf einen anderen Client — und ein Keycloak "
        "mit eigenem Volume liest den Realm-Export nach dem ersten Start nicht mehr, die Datei "
        "allein genügt dort also nicht."
    ),
    "dev": (
        "Dieses Profil authentifiziert über den Kopf 'X-OPAA-Dev-User', nicht über ein Token. "
        "Häufigste Ursache: DevAuthFilter weist einen Nutzer ab, der nicht unter "
        "opaa.auth.dev.users konfiguriert ist — oder das Backend läuft gar nicht im Auth-Modus "
        "'dev'."
    ),
}


def _readiness_failure_message(
    last_error: Exception | None, timeout_seconds: float, auth_mode: str
) -> str:
    """Names the failure the readiness wait actually ran into (#1515). "Nicht erreichbar" is
    reserved for the case where nothing answered; an answered request that rejects the caller is
    reported as such, together with the WWW-Authenticate challenge that carries the reason. Every
    case stays retried: a token can legitimately be rejected while the backend is still building
    the JwtDecoder of its OIDC provider (ADR-0025)."""
    if isinstance(last_error, ApiError) and last_error.status_code in TOKEN_REJECTED_STATUS_CODES:
        return (
            f"Anmeldung nach {timeout_seconds}s weiterhin abgelehnt "
            f"(HTTP {last_error.status_code}) — das Backend antwortet also, abgewiesen wird die "
            f"Authentifizierung.\n  {last_error}\n" + REJECTION_HINTS.get(auth_mode, "")
        )
    if isinstance(last_error, AuthError):
        return (
            f"Keycloak lehnt die Anmeldung nach {timeout_seconds}s weiterhin ab (letzter Fehler: "
            f"{last_error}). Keycloak antwortet also — geprüft werden sollten Realm, Client "
            "'opaa-seed' (aktiviert, directAccessGrantsEnabled) und die Zugangsdaten des Kontos."
        )
    if isinstance(last_error, ApiError):
        return (
            f"Backend antwortet nach {timeout_seconds}s weiterhin mit einem Fehler (letzter "
            f"Fehler: {last_error}). Erreichbar ist es also — der Fehler liegt hinter der "
            "Anfrage, nicht in der Verbindung."
        )
    return (
        f"Backend bzw. Keycloak nach {timeout_seconds}s nicht erreichbar (letzter Fehler: "
        f"{last_error}). Läuft der Stack bereits vollständig (docker compose ... up, ggf. "
        "--profile demo)?"
    )


def provision_users(
    clients: dict[str, Client], profile: Profile, bootstrap_admin: Client | None
) -> dict[str, str]:
    """Triggers UserProvisioningFilter for every user by making one authenticated request each,
    and returns each user's database id, keyed by the profile's own user key. In the Keycloak
    profile the admin account no longer becomes SYSTEM_ADMIN by itself (ADR-0033: the first
    administrator is the local bootstrap account the backend seeds); the role is granted here by
    that bootstrap account, once, through the regular role API."""
    user_ids: dict[str, str] = {}
    for user in profile.all_users():
        info = clients[user.key].get_ok("/v1/auth/me")
        user_ids[user.key] = info["id"]
        print(f"  Nutzer bereitgestellt: {user.display_name} ({info['systemRole']}, {info['id']})")
    admin_role = clients[profile.admin.key].get_ok("/v1/auth/me")["systemRole"]
    if admin_role != "SYSTEM_ADMIN" and bootstrap_admin is not None:
        bootstrap_admin.post_ok(
            f"/v1/admin/users/{user_ids[profile.admin.key]}/role", json={"role": "SYSTEM_ADMIN"}
        )
        print(f"  SYSTEM_ADMIN an {profile.admin.display_name} vergeben (durch das Notanker-Konto)")
        admin_role = clients[profile.admin.key].get_ok("/v1/auth/me")["systemRole"]
    if admin_role != "SYSTEM_ADMIN":
        raise SystemExit(
            f"Admin-Konto '{profile.admin.identity}' hat nicht die Rolle SYSTEM_ADMIN "
            f"(tatsaechlich: {admin_role}). Im Keycloak-Profil vergibt der Seed die Rolle ueber das "
            "lokale Notanker-Konto der Systemverwaltung (ADR-0033): OPAA_INITIAL_ADMIN_EMAIL und "
            "OPAA_INITIAL_ADMIN_PASSWORD muessen beim allerersten Start des Stacks gesetzt gewesen "
            "sein und dem Seed als --local-admin-email/--local-admin-password bzw. als "
            "Umgebungsvariablen vorliegen. Abhilfe: den Stack mit 'docker compose ... down -v' "
            "zuruecksetzen und den Seed erneut laufen lassen."
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
    if library_def.source_credentials:
        body["sourceCredentials"] = library_def.source_credentials
    if library_def.s3_settings:
        body["s3Settings"] = library_def.s3_settings
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


def ensure_grant(
    admin_client: Client,
    library_id: str,
    subject_id: str,
    role: str = "VIEWER",
    subject_type: str = "USER",
) -> None:
    # upsertAssetGrant is idempotent per subject by design (see opaa-api.yaml) - always safe to call.
    admin_client.post_ok(
        f"/v1/libraries/{library_id}/grants",
        json={"subjectType": subject_type, "subjectId": subject_id, "role": role},
        expected=(200,),
    )


def ensure_groups_claim(admin_client: Client, display_name: str = "Verzeichnisdienst") -> None:
    """Sets the default provider's groups_claim to 'groups' (ADR-0036, Entscheidungen 2/3) -
    matching the group-membership-mapper keycloak/realm-export.json's opaa-frontend and opaa-seed
    clients both carry. Idempotent: a PUT that changes nothing still succeeds."""
    providers = admin_client.get_ok("/v1/admin/oidc-providers")
    provider = next((p for p in providers if p["displayName"] == display_name), None)
    if provider is None:
        raise SystemExit(
            f"Anbieter '{display_name}' nicht gefunden - der Bootstrap aus OPAA_OIDC_* ist "
            "offenbar noch nicht abgeschlossen."
        )
    claim_mapping = dict(provider.get("claimMapping") or {})
    if claim_mapping.get("groupsClaim") == "groups":
        print(f"  groups_claim bereits gesetzt: {display_name}")
        return
    claim_mapping["groupsClaim"] = "groups"
    admin_client.put_ok(
        f"/v1/admin/oidc-providers/{provider['id']}",
        json={
            "displayName": provider["displayName"],
            "issuerUri": provider["issuerUri"],
            "clientId": provider["clientId"],
            "jwkSetUri": provider.get("jwkSetUri"),
            "claimMapping": claim_mapping,
        },
    )
    print(f"  groups_claim gesetzt: {display_name} -> 'groups'")


def reprovision_all(clients: dict[str, Client], profile: Profile) -> None:
    """Re-authenticates every profile account once groups_claim is set (ensure_groups_claim
    above), so TokenGroupSynchronizer picks up each account's Keycloak group memberships:
    UserProvisioningFilter re-provisions on *every* request, not only the first, but the very
    first sign-in in step 1 ran before the provider carried a groups_claim and left every
    membership unsynchronised."""
    for user in profile.all_users():
        clients[user.key].get_ok("/v1/auth/me")


def ensure_group(admin_client: Client, user_ids: dict[str, str], group_def: GroupDef) -> str:
    """Idempotency: every demo group is looked up by name via GET /v1/admin/groups, the one path
    that lists every group of the organization regardless of who stewards it (AdminGroupController,
    ADR-0036 Entscheidung 4)."""
    existing = admin_client.get_ok("/v1/admin/groups")
    group = next((g for g in existing if g["name"] == group_def.name), None)
    if group is None:
        created = admin_client.post_ok(
            "/v1/groups",
            json={"name": group_def.name, "description": group_def.description},
            expected=(201,),
        )
        group_id = created["id"]
        print(f"  Gruppe angelegt: {group_def.name} ({group_id})")
    else:
        group_id = group["id"]
        print(f"  Gruppe bereits vorhanden: {group_def.name}")

    steward_ids = {user_ids[key] for key in group_def.steward_keys}
    current_stewards = {s["userId"] for s in admin_client.get_ok(f"/v1/groups/{group_id}/stewards")}
    for steward_id in steward_ids - current_stewards:
        admin_client.post_ok(
            f"/v1/groups/{group_id}/stewards", json={"userId": steward_id}, expected=(201,)
        )

    # createGroup auto-appoints its caller (the admin account) as the group's first steward - the
    # demo names the profile's own stewards, not the admin, so that auto-appointment is withdrawn
    # once they are in place (ADR-0036, Entscheidung 4: "benannte Verantwortliche").
    admin_id = user_ids["admin"]
    if admin_id not in steward_ids:
        current_stewards = {
            s["userId"] for s in admin_client.get_ok(f"/v1/groups/{group_id}/stewards")
        }
        if admin_id in current_stewards:
            admin_client.delete_ok(f"/v1/groups/{group_id}/stewards/{admin_id}")

    member_ids = {user_ids[key] for key in group_def.member_keys}
    current_members = {m["userId"] for m in admin_client.get_ok(f"/v1/groups/{group_id}/members")}
    for member_id in member_ids - current_members:
        admin_client.post_ok(
            f"/v1/groups/{group_id}/members", json={"userId": member_id}, expected=(201,)
        )

    admin_client.put_ok(
        f"/v1/groups/{group_id}/release", json={"releasedForUse": group_def.released_for_use}
    )
    return group_id


def ensure_group_space_membership(
    owner_client: Client, space_id: str, group_id: str, role: str
) -> None:
    existing = owner_client.get_ok(f"/v1/spaces/{space_id}/members")
    if any(m["subjectType"] == "GROUP" and m["subjectId"] == group_id for m in existing):
        return
    owner_client.post_ok(
        f"/v1/spaces/{space_id}/members",
        json={"subjectType": "GROUP", "subjectId": group_id, "role": role},
        expected=(201,),
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


def expected_document_count(library_def: LibraryDef) -> int | None:
    """How many documents a run over library_def's own corpus directory has to end up with, or None
    when the profile names no such directory. The bucket of an S3 library is an exact mirror of it,
    so one file is one document."""
    if library_def.expected_documents_dir is None:
        return None
    if not library_def.expected_documents_dir.is_dir():
        raise SystemExit(
            f"Korpusverzeichnis für '{library_def.name}' fehlt: "
            f"{library_def.expected_documents_dir}"
        )
    return sum(1 for path in library_def.expected_documents_dir.rglob("*") if path.is_file())


def trigger_indexing(
    admin_client: Client,
    library_id: str,
    name: str,
    timeout_seconds: int,
    expected_documents: int | None = None,
) -> None:
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
    # A run that saw nothing also ends COMPLETED. Against a bucket the "minio-seed" step has not
    # finished filling, that would report success over a half-filled - or empty - library.
    # Unchanged documents count as skipped on a repeat run, so both numbers belong in the total.
    processed = status["documentsIndexedTotal"] + status["documentsSkipped"]
    if expected_documents is not None and processed < expected_documents:
        raise SystemExit(
            f"Indizierung für '{name}' hat nur {processed} von erwarteten {expected_documents} "
            f"Dokumenten verarbeitet (indiziert: {status['documentsIndexedTotal']}, übersprungen: "
            f"{status['documentsSkipped']}). Bei einer S3-Bibliothek heißt das meist: Der "
            "Einmal-Schritt 'minio-seed' war beim Auslösen noch nicht fertig - "
            "'docker compose logs minio-seed' prüfen und den Seed erneut laufen lassen."
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
    bootstrap_admin: Client | None = None
    if profile.auth_mode == "keycloak" and args.local_admin_email and args.local_admin_password:
        bootstrap_admin = Client(
            base_url=args.base_url,
            auth=LocalPasswordAuth(
                args.base_url, args.local_admin_email, args.local_admin_password
            ),
            rate_limit_wait_seconds=args.rate_limit_wait_seconds,
            label="notanker",
        )

    print("Warte auf Backend/Keycloak …")
    wait_until_ready(admin_client, profile.auth_mode)

    print("1/8 Nutzer bereitstellen (erste authentifizierte Anfrage je Nutzer) …")
    user_ids = provision_users(clients, profile, bootstrap_admin)

    if profile.auth_mode == "keycloak":
        print("2/8 Identitätsanbieter: groups_claim setzen (ADR-0036) …")
        ensure_groups_claim(admin_client)
        reprovision_all(clients, profile)
    else:
        print("2/8 Identitätsanbieter: übersprungen (kein OIDC-Anbieter im dev-Betriebsmodus) …")

    print("3/8 Spaces einrichten …")
    space_ids: dict[str, str] = {}
    for space_def in profile.spaces:
        space_ids[space_def.name] = ensure_space(admin_client, clients, user_ids, space_def)

    print("4/8 Wissensbibliotheken einrichten …")
    library_ids: dict[str, str] = {}
    for library_def in profile.libraries:
        library_ids[library_def.name] = ensure_library(admin_client, library_def)

    print("5/8 Leserechte (VIEWER) und Upload-Dokumente …")
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

    print("6/8 Gruppen einrichten (ADR-0036) …")
    space_owner_by_name = {space_def.name: space_def.owner_key for space_def in profile.spaces}
    for group_def in profile.groups:
        group_id = ensure_group(admin_client, user_ids, group_def)
        for library_name in group_def.library_grants:
            ensure_grant(
                admin_client, library_ids[library_name], group_id, subject_type="GROUP"
            )
            print(f"  Leserecht (Gruppe) vergeben: {group_def.name} → {library_name}")
        if group_def.space_membership:
            space_name, role = group_def.space_membership
            ensure_group_space_membership(
                clients[space_owner_by_name[space_name]], space_ids[space_name], group_id, role
            )
            print(f"  Space-Mitglied (Gruppe): {group_def.name} ∈ {space_name} ({role})")

    print("7/8 Space↔Bibliothek-Zuordnungen (Assoziation als Kuratierung, #706) …")
    for space_def in profile.spaces:
        for library_name in space_def.library_names:
            if library_name not in library_ids:
                raise SystemExit(
                    f"Space '{space_def.name}' referenziert eine unbekannte Bibliothek "
                    f"'{library_name}' - library_names muss auf eine LibraryDef des Profils zeigen."
                )
            # After step 5 the owner holds VIEWER on the library (grants) and is CURATOR or above
            # on their own space - exactly what associateSpaceLibrary requires.
            ensure_association(
                clients[space_def.owner_key],
                space_ids[space_def.name],
                library_ids[library_name],
            )
            print(f"  zugeordnet: {space_def.name} ← {library_name}")

    print("8/8 Indizierung je Bibliothek auslösen (ADR-0018) …")
    for library_def in profile.libraries:
        if library_def.source_type == "UPLOAD":
            # UPLOAD has no run of its own (ADR-0018) - indexing happens per document on upload.
            continue
        trigger_indexing(
            admin_client,
            library_ids[library_def.name],
            library_def.name,
            timeout_seconds=args.indexing_timeout_seconds,
            expected_documents=expected_document_count(library_def),
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
    parser.add_argument(
        "--local-admin-email",
        default=os.environ.get("OPAA_INITIAL_ADMIN_EMAIL", ""),
        help="E-mail of the local bootstrap administrator (ADR-0033) the 'demo' profile signs in "
        "as to grant SYSTEM_ADMIN to the Keycloak admin (default: $OPAA_INITIAL_ADMIN_EMAIL)",
    )
    parser.add_argument(
        "--local-admin-password",
        default=os.environ.get("OPAA_INITIAL_ADMIN_PASSWORD", ""),
        help="Password of that account (default: $OPAA_INITIAL_ADMIN_PASSWORD)",
    )
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
