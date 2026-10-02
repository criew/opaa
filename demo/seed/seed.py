#!/usr/bin/env python3
"""Shared seed mechanism for the OPAA demo/E2E data profiles (Issue #712).

Sets up a ready-to-use OPAA installation through the public API only (no direct database access,
per the issue's "Technische Hinweise"): users (provisioned by their first authenticated request),
spaces, knowledge libraries with their own source configuration (ADR-0018), VIEWER grants,
space<->library associations (#706, pure curation), upload documents, the indexing run per
library and, last, prepared chats with their sources (chats.py; the import route exists only
while the backend runs with OPAA_DEMO_CHAT_IMPORT_ENABLED=true).

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
from datetime import datetime, timezone
from pathlib import Path

import requests

import chats
from api_client import ApiError, Client
from auth import AuthError, DevHeaderAuth, KeycloakPasswordAuth, LocalPasswordAuth
from profiles import PROFILES, GroupDef, LibraryDef, Profile, SpaceDef, UserDef
from profiles import DirectorySyncDef, ProviderGroupDef
from profiles import PromptDef, PromptLibraryDef, PromptVariableDef

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
        # No grant to "Alle Konten" on any knowledge library of the demo (#1931,
        # docs/features/spaces-and-assets.md): such a grant reaches every account regardless of the
        # demo's own VIEWER matrix (Thomas must not read the internal Meldewesen instructions).
    }
    if library_def.source_url:
        body["sourceUrl"] = library_def.source_url
    if library_def.source_credentials:
        body["sourceCredentials"] = library_def.source_credentials
    if library_def.s3_settings:
        body["sourceSettings"] = library_def.s3_settings
    created = admin_client.post_ok("/v1/libraries", json=body, expected=(201,))
    print(f"  Bibliothek angelegt: {library_def.name} ({created['id']})")
    return created["id"]


def ensure_association(owner_client: Client, space_id: str, library_id: str) -> None:
    # associateSpaceAsset is idempotent by design (see opaa-api.yaml): an already-associated
    # asset returns its existing association unchanged, also with 201.
    owner_client.post_ok(
        f"/v1/spaces/{space_id}/assets",
        json={"assetType": "KNOWLEDGE_LIBRARY", "assetId": library_id},
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
        f"/v1/assets/KNOWLEDGE_LIBRARY/{library_id}/grants",
        json={"subjectType": subject_type, "subjectId": subject_id, "role": role},
        expected=(200,),
    )


DIRECTORY_SYNC_RUN_ATTEMPTS = 40
REALM_SCRIPT_HINT = (
    "Ein Keycloak mit eigenem Volume liest keycloak/realm-export.json nicht erneut ein - dort "
    "demo/keycloak/apply-realm-changes.sh ausführen (siehe demo/README.md)."
)


def find_provider(admin_client: Client, display_name: str = "Verzeichnisdienst") -> dict:
    providers = admin_client.get_ok("/v1/admin/oidc-providers")
    provider = next((p for p in providers if p["displayName"] == display_name), None)
    if provider is None:
        raise SystemExit(
            f"Anbieter '{display_name}' nicht gefunden - der Bootstrap aus OPAA_OIDC_* ist "
            "offenbar noch nicht abgeschlossen."
        )
    return provider


def keycloak_admin_base_url(jwk_set_uri: str | None) -> str | None:
    """The address under which the backend reaches Keycloak: the JWK set address without its
    /realms/{realm}/... part. None when the provider names no Keycloak JWK set address - the
    backend then derives the address from the issuer URI."""
    if not jwk_set_uri or "/realms/" not in jwk_set_uri:
        return None
    return jwk_set_uri.split("/realms/", 1)[0]


def _probe_directory_connector(admin_client: Client, provider_id: str, connector: dict) -> dict:
    """Probes with the stored secret: the request leaves clientSecret out."""
    probe = {key: value for key, value in connector.items() if key != "clientSecret"}
    return admin_client.post_ok(
        f"/v1/admin/oidc-providers/{provider_id}/directory-connector/test",
        json=probe,
        expected=(200,),
    )


def ensure_directory_sync(
    admin_client: Client,
    directory_sync: DirectorySyncDef,
    client_secret: str | None = None,
    display_name: str = "Verzeichnisdienst",
) -> str:
    """Switches the provider from the groups claim to the directory sync (ADR-0036, Entscheidung
    2/3: one group mechanism per provider), stores the Keycloak connector, enables the scheduled
    run and runs it once, so the realm's groups exist as ORG_UNIT groups before step 6 gives them
    rights. Returns the provider id. Writes only what differs; the run happens every time.

    client_secret is the secret given explicitly; without it the profile's demo value is used, but
    only for a connection not stored yet - a stored one whose probe fails is never overwritten."""
    provider = find_provider(admin_client, display_name)
    provider_id = provider["id"]
    provider_path = f"/v1/admin/oidc-providers/{provider_id}"

    claim_mapping = dict(provider.get("claimMapping") or {})
    if claim_mapping.get("groupsClaim"):
        claim_mapping["groupsClaim"] = None
        admin_client.put_ok(
            provider_path,
            json={
                "displayName": provider["displayName"],
                "issuerUri": provider["issuerUri"],
                "clientId": provider["clientId"],
                "jwkSetUri": provider.get("jwkSetUri"),
                "claimMapping": claim_mapping,
            },
        )
        print(f"  groups_claim geleert: {display_name} (Gruppen kommen aus dem Verzeichnis)")

    connector = {
        "type": "KEYCLOAK",
        "baseUrl": keycloak_admin_base_url(provider.get("jwkSetUri")),
        "clientId": directory_sync.client_id,
        "clientSecret": client_secret or directory_sync.client_secret,
    }
    stored = provider.get("directoryConnector") or {}
    already_stored = stored.get("clientId") == connector["clientId"] and (
        connector["baseUrl"] is None or stored.get("baseUrl") == connector["baseUrl"]
    )
    probe = (
        _probe_directory_connector(admin_client, provider_id, connector) if already_stored else None
    )
    if probe is not None and not probe["success"] and not client_secret:
        raise SystemExit(
            f"Verbindungstest des hinterlegten Verzeichniszugangs fehlgeschlagen: {probe['message']} "
            "Entweder ist Keycloak gerade nicht erreichbar, oder das hinterlegte Geheimnis passt "
            f"nicht mehr zum Dienstkonto '{directory_sync.client_id}'. Der Seed ersetzt einen "
            "hinterlegten Zugang nie durch den Demo-Wert; ein neues Geheimnis ausdrücklich "
            "übergeben (--directory-client-secret bzw. OPAA_DEMO_DIRECTORY_CLIENT_SECRET) oder "
            "unter Administration → Verzeichnisabgleich hinterlegen."
        )
    if probe is None or not probe["success"]:
        admin_client.put_ok(f"{provider_path}/directory-connector", json=connector)
        print(f"  Verzeichniszugang hinterlegt: {connector['clientId']} → {connector['baseUrl']}")
        probe = _probe_directory_connector(admin_client, provider_id, connector)
    if not probe["success"]:
        raise SystemExit(
            f"Verbindungstest des Verzeichniszugangs fehlgeschlagen: {probe['message']} "
            f"Das Dienstkonto '{directory_sync.client_id}' braucht im Realm dasselbe Geheimnis, das "
            "der Seed übergibt, und die Rollen view-users und query-groups. " + REALM_SCRIPT_HINT
        )
    print(f"  Verbindungstest: {probe['message']}")

    if (
        not provider.get("directorySyncEnabled")
        or provider.get("directorySyncIntervalMinutes") != directory_sync.interval_minutes
    ):
        admin_client.put_ok(
            f"{provider_path}/directory-sync",
            json={"enabled": True, "intervalMinutes": directory_sync.interval_minutes},
        )
        print(f"  Verzeichnisabgleich eingeschaltet: alle {directory_sync.interval_minutes} Minuten")

    run_directory_sync(admin_client, provider_id)
    return provider_id


def _error_code(response: requests.Response) -> str | None:
    try:
        return response.json().get("code")
    except ValueError:
        return None


def run_directory_sync(admin_client: Client, provider_id: str) -> dict:
    """Runs the directory sync on demand and stops the seed unless it applied. A provider that was
    never run is due at once, so the scheduler's minute tick may be running it already - that 409
    is waited out."""
    path = f"/v1/admin/oidc-providers/{provider_id}/directory-sync/run"
    for _ in range(DIRECTORY_SYNC_RUN_ATTEMPTS):
        response = admin_client.post(path)
        if response.status_code == 409 and _error_code(response) == "DIRECTORY_SYNC_ALREADY_RUNNING":
            time.sleep(3)
            continue
        if response.status_code != 200:
            raise ApiError(response)
        report = response.json()
        break
    else:
        raise SystemExit(
            "Verzeichnisabgleich: Ein bereits laufender Abgleich wurde nicht fertig - Stand unter "
            "Administration → Verzeichnisabgleich prüfen."
        )
    if report["outcome"] != "APPLIED":
        raise SystemExit(
            f"Verzeichnisabgleich nicht angewendet (Ergebnis {report['outcome']}): "
            f"{report.get('message')} - Stand und ggf. ausstehenden Plan unter Administration → "
            "Verzeichnisabgleich prüfen."
        )
    created = ", ".join(g["name"] for g in report.get("groupsCreated", [])) or "keine"
    print(
        f"  Verzeichnisabgleich angewendet: neue Gruppen {created}, "
        f"{report.get('membershipsAdded', 0)} Mitgliedschaft(en) hinzugefügt"
    )
    locked = report.get("accountsLocked") or []
    if locked:
        names = ", ".join(a.get("displayName") or a["userId"] for a in locked)
        print(f"  Achtung: Der Abgleich hat {len(locked)} Konto/Konten gesperrt: {names}")
    return report


def find_provider_group(admin_client: Client, provider_id: str, group_def: ProviderGroupDef) -> str:
    """The ORG_UNIT group the directory sync made of the realm group. A same-named token group of
    the provider - left behind unmaintained by the switch from the groups claim - and a dissolved
    unit are not it."""
    for group in admin_client.get_ok("/v1/admin/groups"):
        if (
            group["name"] == group_def.name
            and group["kind"] == "ORG_UNIT"
            and (group.get("provider") or {}).get("id") == provider_id
            and not group["dissolved"]
        ):
            return group["id"]
    raise SystemExit(
        f"Keycloak-Gruppe '{group_def.name}' ist nach dem Verzeichnisabgleich nicht in OPAA "
        "angekommen - sie fehlt im Realm. " + REALM_SCRIPT_HINT
    )


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


def _prompt_variable_body(variable_def: PromptVariableDef) -> dict:
    body: dict = {
        "name": variable_def.name,
        "label": variable_def.label,
        "type": variable_def.type,
        "required": variable_def.required,
    }
    if variable_def.default_value is not None:
        body["defaultValue"] = variable_def.default_value
    if variable_def.options:
        body["options"] = list(variable_def.options)
    return body


def prompt_request_body(prompt_def: PromptDef) -> dict:
    """The PromptRequest of prompts.yaml; optional fields the definition leaves open stay absent."""
    body: dict = {
        "name": prompt_def.name,
        "title": prompt_def.title,
        "text": prompt_def.text,
        "sortOrder": prompt_def.sort_order,
    }
    if prompt_def.description is not None:
        body["description"] = prompt_def.description
    if prompt_def.variables:
        body["variables"] = [_prompt_variable_body(v) for v in prompt_def.variables]
    return body


def ensure_prompt_library(
    owner_client: Client, owner_id: str, library_def: PromptLibraryDef
) -> str:
    """Idempotency: the owner holds OWNER on their own library, so listPromptLibraries through the
    owner's session always includes it. The lookup also matches the owner, because the list holds
    every library the caller may read - a same-named one shared by someone else is not this one."""
    for library in owner_client.get_ok("/v1/prompt-libraries"):
        if (
            library["name"] == library_def.name
            and library["ownerType"] == "USER"
            and library["ownerId"] == owner_id
        ):
            print(f"  Prompt-Bibliothek bereits vorhanden: {library_def.name}")
            return library["id"]
    created = owner_client.post_ok(
        "/v1/prompt-libraries",
        json={
            "name": library_def.name,
            "description": library_def.description,
        },
        expected=(201,),
    )
    print(f"  Prompt-Bibliothek angelegt: {library_def.name} ({created['id']})")
    return created["id"]


def ensure_prompt_library_grants(
    owner_client: Client, library_id: str, library_def: PromptLibraryDef, user_ids: dict[str, str]
) -> None:
    # upsertAssetGrant is idempotent per subject; ALL_ACCOUNTS names no subjectId.
    grants = []
    if library_def.all_accounts_viewer:
        grants.append({"subjectType": "ALL_ACCOUNTS", "role": "VIEWER"})
    grants += [
        {"subjectType": "USER", "subjectId": user_ids[key], "role": "VIEWER"}
        for key in library_def.viewer_keys
    ]
    for grant in grants:
        owner_client.post_ok(
            f"/v1/assets/PROMPT_LIBRARY/{library_id}/grants", json=grant, expected=(200,)
        )


def ensure_prompts(owner_client: Client, library_id: str, library_def: PromptLibraryDef) -> None:
    """Creates every prompt not yet present by name; an existing prompt is left as it is."""
    prompts_path = f"/v1/prompt-libraries/{library_id}/prompts"
    existing = {prompt["name"] for prompt in owner_client.get_ok(prompts_path)}
    for prompt_def in library_def.prompts:
        if prompt_def.name in existing:
            print(f"    Prompt bereits vorhanden: /{prompt_def.name}")
            continue
        owner_client.post_ok(
            prompts_path,
            json=prompt_request_body(prompt_def),
            expected=(201,),
        )
        print(f"    Prompt angelegt: /{prompt_def.name}")


def seed_prompt_libraries(
    clients: dict[str, Client],
    user_ids: dict[str, str],
    space_ids: dict[str, str],
    profile: Profile,
) -> None:
    """Creates each prompt library through its owner's session, gives its grants, fills in its
    prompts and associates it with its spaces through each space owner's session - after the
    grants, because associateSpaceAsset requires that owner to read the library."""
    space_owner_by_name = {space_def.name: space_def.owner_key for space_def in profile.spaces}
    for library_def in profile.prompt_libraries:
        owner_client = clients[library_def.owner_key]
        library_id = ensure_prompt_library(
            owner_client, user_ids[library_def.owner_key], library_def
        )
        ensure_prompt_library_grants(owner_client, library_id, library_def, user_ids)
        ensure_prompts(owner_client, library_id, library_def)
        for space_name in library_def.space_names:
            if space_name not in space_ids:
                raise SystemExit(
                    f"Prompt-Bibliothek '{library_def.name}' referenziert einen unbekannten Space "
                    f"'{space_name}' - space_names muss auf eine SpaceDef des Profils zeigen."
                )
            clients[space_owner_by_name[space_name]].post_ok(
                f"/v1/spaces/{space_ids[space_name]}/assets",
                json={"assetType": "PROMPT_LIBRARY", "assetId": library_id},
                expected=(201,),
            )
            print(f"  zugeordnet: {space_name} ← {library_def.name}")


def existing_documents(admin_client: Client, library_id: str) -> dict[tuple[str, str], dict]:
    """Every document of the library keyed by (folder path, file name), the folder path relative
    to the library root with "/" between levels and "" for the root. The document list only shows
    one folder level per request, so this walks the folder tree from the root."""
    found: dict[tuple[str, str], dict] = {}
    pending: list[tuple[str | None, str]] = [(None, "")]
    while pending:
        folder_id, folder_path = pending.pop()
        subfolders: dict[str, str] = {}
        page = 0
        while True:
            params = {"page": page, "size": 100}
            if folder_id is not None:
                params["folderId"] = folder_id
            result = admin_client.get_ok(f"/v1/libraries/{library_id}/documents", params=params)
            for item in result["items"]:
                found[(folder_path, item["fileName"])] = item
            for folder in result.get("folders", []):
                subfolders[folder["id"]] = folder["name"]
            if (page + 1) * result["size"] >= result["totalElements"] or not result["items"]:
                break
            page += 1
        for subfolder_id, name in subfolders.items():
            pending.append((subfolder_id, f"{folder_path}/{name}" if folder_path else name))
    return found


def upload_documents(admin_client: Client, library_id: str, upload_dir: Path) -> None:
    """Uploads every file below upload_dir not already present with status PENDING/INDEXED, into
    the library folder matching its subdirectory (folderPath, created on demand by the API). A
    document counts as present only under the same folder path and file name. One whose previous
    attempt ended FAILED is re-uploaded rather than skipped.

    Stops before the first upload when the library holds, in its root, a file that belongs in a
    subfolder: the API would create the folder chain and then reject the same content with 409."""
    existing = existing_documents(admin_client, library_id)
    local_files = []
    for file_path in sorted(p for p in upload_dir.rglob("*") if p.is_file()):
        folder_path = file_path.parent.relative_to(upload_dir).as_posix()
        local_files.append(("" if folder_path == "." else folder_path, file_path))

    misplaced = sorted(
        {
            file_path.name
            for folder_path, file_path in local_files
            if folder_path
            and ("", file_path.name) in existing
            and not any(f == "" and p.name == file_path.name for f, p in local_files)
        }
    )
    if misplaced:
        raise SystemExit(
            f"Die Bibliothek führt {len(misplaced)} Dokument(e) noch flach in der Wurzel, die "
            f"inzwischen in Ordnern liegen (z. B. {', '.join(misplaced[:3])}). Ein Seed kann sie "
            "nicht umsortieren - die Demo neu aufsetzen (siehe demo/README.md)."
        )

    for folder_path, file_path in local_files:
        label = f"{folder_path}/{file_path.name}" if folder_path else file_path.name
        current = existing.get((folder_path, file_path.name))
        if current is not None and current["status"] != "FAILED":
            print(f"    bereits hochgeladen: {label} ({current['status']})")
            continue
        if current is not None:
            print(f"    erneuter Versuch nach FAILED: {label} ({current.get('errorMessage')})")
        content_type = mimetypes.guess_type(file_path.name)[0] or "application/octet-stream"
        with file_path.open("rb") as handle:
            try:
                admin_client.post_ok(
                    f"/v1/libraries/{library_id}/documents",
                    files={"file": (file_path.name, handle, content_type)},
                    data={"folderPath": folder_path} if folder_path else None,
                    expected=(201,),
                )
            except ApiError as error:
                if error.status_code != 409:
                    raise
                raise SystemExit(
                    f"Upload von '{label}' abgelehnt: Derselbe Inhalt liegt bereits an anderer "
                    "Stelle dieser Bibliothek. Ein Seed kann Dokumente nicht verschieben - die "
                    "Demo neu aufsetzen (siehe demo/README.md)."
                ) from error
        print(f"    hochgeladen: {label}")


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
        documents = list(existing_documents(admin_client, library_id).values())
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
    nested folders included, so one file is one document. Attachments of a mail object are further
    documents but stay out of the count: a repeat run skips an unchanged mail without counting its
    attachments, and the count is a lower bound for both runs."""
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
    # A run that saw nothing also ends COMPLETED. Against a bucket the "objectstore-seed" step has not
    # finished filling, that would report success over a half-filled - or empty - library.
    # Unchanged documents count as skipped on a repeat run, so both numbers belong in the total.
    processed = status["documentsIndexedTotal"] + status["documentsSkipped"]
    if expected_documents is not None and processed < expected_documents:
        raise SystemExit(
            f"Indizierung für '{name}' hat nur {processed} von erwarteten {expected_documents} "
            f"Dokumenten verarbeitet (indiziert: {status['documentsIndexedTotal']}, übersprungen: "
            f"{status['documentsSkipped']}). Bei einer S3-Bibliothek heißt das meist: Der "
            "Einmal-Schritt 'objectstore-seed' war beim Auslösen noch nicht fertig - "
            "'docker compose logs objectstore-seed' prüfen und den Seed erneut laufen lassen."
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

    print("1/9 Nutzer bereitstellen (erste authentifizierte Anfrage je Nutzer) …")
    user_ids = provision_users(clients, profile, bootstrap_admin)

    provider_id: str | None = None
    if profile.directory_sync is not None:
        print("2/9 Identitätsanbieter: Verzeichnisabgleich einrichten und ausführen (ADR-0036) …")
        provider_id = ensure_directory_sync(
            admin_client, profile.directory_sync, client_secret=args.directory_client_secret
        )
    else:
        print("2/9 Identitätsanbieter: übersprungen (kein Verzeichnisabgleich im Profil) …")

    print("3/9 Spaces einrichten …")
    space_ids: dict[str, str] = {}
    for space_def in profile.spaces:
        space_ids[space_def.name] = ensure_space(admin_client, clients, user_ids, space_def)

    print("4/9 Wissensbibliotheken einrichten …")
    library_ids: dict[str, str] = {}
    for library_def in profile.libraries:
        library_ids[library_def.name] = ensure_library(admin_client, library_def)

    print("5/9 Leserechte (VIEWER) und Upload-Dokumente …")
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

    print("6/9 Gruppen einrichten und Rechte der Keycloak-Gruppen vergeben (ADR-0036) …")
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
    for provider_group_def in profile.provider_groups:
        group_id = find_provider_group(admin_client, provider_id, provider_group_def)
        for library_name in provider_group_def.library_grants:
            ensure_grant(admin_client, library_ids[library_name], group_id, subject_type="GROUP")
            print(f"  Leserecht (Keycloak-Gruppe) vergeben: {provider_group_def.name} → {library_name}")
        if provider_group_def.space_membership:
            space_name, role = provider_group_def.space_membership
            ensure_group_space_membership(
                clients[space_owner_by_name[space_name]], space_ids[space_name], group_id, role
            )
            print(
                f"  Space-Mitglied (Keycloak-Gruppe): {provider_group_def.name} ∈ {space_name} "
                f"({role})"
            )

    print("7/9 Space↔Bibliothek-Zuordnungen (Assoziation als Kuratierung, #706) …")
    for space_def in profile.spaces:
        for library_name in space_def.library_names:
            if library_name not in library_ids:
                raise SystemExit(
                    f"Space '{space_def.name}' referenziert eine unbekannte Bibliothek "
                    f"'{library_name}' - library_names muss auf eine LibraryDef des Profils zeigen."
                )
            # After step 6 the owner holds VIEWER on the library (own or group grant) and is
            # CURATOR or above on their own space - exactly what associateSpaceLibrary requires.
            ensure_association(
                clients[space_def.owner_key],
                space_ids[space_def.name],
                library_ids[library_name],
            )
            print(f"  zugeordnet: {space_def.name} ← {library_name}")

    print("7b/9 Prompt-Bibliotheken mit Prompts, Freigaben und Space-Zuordnung …")
    seed_prompt_libraries(clients, user_ids, space_ids, profile)

    print("8/9 Indizierung je Bibliothek auslösen (ADR-0018) …")
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

    print("9/9 Vorbereitete Chats einspielen (ohne Modellaufruf, #2071) …")
    seed_chats(clients, admin_client, space_ids, library_ids, profile)

    print(f"Seed-Profil '{profile.name}' abgeschlossen.")


def seed_chats(
    clients: dict[str, Client],
    admin_client: Client,
    space_ids: dict[str, str],
    library_ids: dict[str, str],
    profile: Profile,
) -> None:
    """Imports each chat set of the profile into its space, through the owner's own session - the
    import makes the caller the author. Sources resolve against the admin's document lists: the
    admin owns every library, so every document is listed there."""
    if not profile.chat_sets:
        print("  übersprungen (keine vorbereiteten Chats im Profil)")
        return
    space_owner_by_name = {space_def.name: space_def.owner_key for space_def in profile.spaces}
    now = datetime.now(timezone.utc)
    for directory in profile.chat_sets:
        chat_set = chats.load_chat_set(directory)
        if space_owner_by_name.get(chat_set.space) != chat_set.owner_key:
            raise SystemExit(
                f"Chat-Satz {directory.name}: '{chat_set.owner_key}' ist nicht Eigentümerin bzw. "
                f"Eigentümer des Space '{chat_set.space}'."
            )
        resolved = chats.resolve_documents(
            chat_set,
            library_ids,
            lambda library_id: list(existing_documents(admin_client, library_id).values()),
        )
        imported, present = chats.seed_chat_set(
            clients[chat_set.owner_key], space_ids[chat_set.space], chat_set, resolved, now
        )
        print(
            f"  {chat_set.space}: {imported} Chat(s) eingespielt, {present} bereits vorhanden "
            f"({len(chat_set.chats)} in {directory.name})"
        )


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
    parser.add_argument(
        "--directory-client-secret",
        default=os.environ.get("OPAA_DEMO_DIRECTORY_CLIENT_SECRET") or None,
        help="Secret of the Keycloak service account opaa-directory for the directory sync "
        "(default: $OPAA_DEMO_DIRECTORY_CLIENT_SECRET; without either, the documented demo value "
        "is used for a connection not stored yet - required on a reachable instance)",
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
