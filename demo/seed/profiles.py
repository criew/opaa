"""The two data profiles the seed mechanism knows (Issue #712, docs/features/demo-instance.md).

"Geteilt sind die Daten, nicht die Nutzerbereitstellung": both profiles describe the same kind of
data (users, spaces, libraries, VIEWER grants, upload documents, source configurations) and are
consumed by the very same seed.py. What differs between them lives entirely in seed.py's choice of
AuthProvider (auth.py) - a profile here only ever names a user by a stable key plus the identity
attributes (subject/username/email/password) that AuthProvider needs, never how the session is
obtained.

- "demo": the rich, evolving Rheinfurt corpus from docs/features/demo-instance.md. Authenticates
  via Keycloak (see keycloak/realm-export.json).
- "e2e": the minimal, frozen profile for the E2E docker-compose stack (e2e/docker-compose.e2e.yml).
  Authenticates via the dev-auth header against the "dev-admin"/"dev-user"/"dev-outsider" accounts
  that stack already provisions (see docker-compose.e2e.yml's OPAA_AUTH_DEV_USERS_* block). Since
  #233, its data (this file plus e2e-data/) is the E2E suite's only source of pre-existing content -
  e2e/fixtures/rss-feed/ and e2e/fixtures/test-documents/ used to be a second, independent way to
  fill an instance and no longer exist; their content lives under e2e-data/ instead, next to the
  profile that governs it. The library below uploads a single dedicated file
  (e2e-data/test-documents/seed/e2e-basisdokument.txt), not the files individual Playwright specs
  upload themselves through the UI (e2e-data/test-documents/*.txt) - those remain each spec's own
  upload input, and granting dev-user a *pre-existing* library containing e.g. wissensdokument.txt
  would defeat knowledge-libraries.spec.ts's own exclusivity assertions (scenario 5 "Entzug wirkt"
  asserts that filename is *not* readable after a share is revoked).
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEMO_CORPUS_ROOT = REPO_ROOT / "demo" / "corpus"
E2E_DATA_ROOT = REPO_ROOT / "demo" / "seed" / "e2e-data"
E2E_SEED_UPLOAD_ROOT = E2E_DATA_ROOT / "test-documents" / "seed"


@dataclass(frozen=True)
class UserDef:
    key: str
    display_name: str
    email: str
    # Keycloak: the realm username. Dev auth: the X-OPAA-Dev-User subject. Either way, the value
    # AuthProvider needs to obtain a session for this user.
    identity: str
    password: str | None = None  # only meaningful for Keycloak


@dataclass(frozen=True)
class SpaceMemberDef:
    user_key: str
    role: str  # SpaceRole: MEMBER, CURATOR, ADMIN


@dataclass(frozen=True)
class SpaceDef:
    name: str
    description: str
    owner_key: str
    members: tuple[SpaceMemberDef, ...] = field(default_factory=tuple)


@dataclass(frozen=True)
class LibraryDef:
    name: str
    description: str
    source_type: str  # DocumentSourceType: HTTP_DIRECTORY, RSS_FEED, UPLOAD
    viewer_keys: tuple[str, ...]
    source_url: str | None = None
    upload_dir: Path | None = None  # every file directly inside is uploaded (non-recursive)


@dataclass(frozen=True)
class Profile:
    name: str
    auth_mode: str  # "keycloak" or "dev"
    admin: UserDef
    users: tuple[UserDef, ...]
    spaces: tuple[SpaceDef, ...]
    libraries: tuple[LibraryDef, ...]

    def all_users(self) -> tuple[UserDef, ...]:
        return (self.admin, *self.users)


DEMO_PASSWORD = "RheinfurtDemo!2026"  # nosec - documented demo credential, see demo/README.md

_DEMO_ADMIN = UserDef(
    key="admin",
    display_name="Admin Rheinfurt",
    email="admin@stadt-rheinfurt.example",
    identity="demo-admin",
    password=DEMO_PASSWORD,
)
_DEMO_MARIA = UserDef(
    key="maria",
    display_name="Maria Weber",
    email="maria.weber@stadt-rheinfurt.example",
    identity="maria.weber",
    password=DEMO_PASSWORD,
)
_DEMO_SELIN = UserDef(
    key="selin",
    display_name="Selin Kaya",
    email="selin.kaya@stadt-rheinfurt.example",
    identity="selin.kaya",
    password=DEMO_PASSWORD,
)
_DEMO_THOMAS = UserDef(
    key="thomas",
    display_name="Thomas Klein",
    email="thomas.klein@stadt-rheinfurt.example",
    identity="thomas.klein",
    password=DEMO_PASSWORD,
)
_DEMO_ANDREA = UserDef(
    key="andrea",
    display_name="Andrea Vogt",
    email="andrea.vogt@stadt-rheinfurt.example",
    identity="andrea.vogt",
    password=DEMO_PASSWORD,
)

DEMO_PROFILE = Profile(
    name="demo",
    auth_mode="keycloak",
    admin=_DEMO_ADMIN,
    users=(_DEMO_MARIA, _DEMO_SELIN, _DEMO_THOMAS, _DEMO_ANDREA),
    spaces=(
        SpaceDef(
            name="Meldewesen & Ausweise",
            description="Gemeinsamer Space des Sachgebiets Meldewesen & Ausweise.",
            owner_key="maria",
            members=(SpaceMemberDef("selin", "MEMBER"),),
        ),
        SpaceDef(
            name="Maria Weber – persönlich",
            description="Persönlicher Arbeitsraum von Maria Weber, kein weiteres Mitglied.",
            owner_key="maria",
        ),
        SpaceDef(
            name="Kfz-Zulassung",
            description="Space des Sachgebiets Kfz-Zulassung.",
            owner_key="thomas",
        ),
        SpaceDef(
            name="Amtsleitung Bürgerbüro",
            description="Space der Amtsleitung des Bürgerbüros Rheinfurt.",
            owner_key="andrea",
        ),
    ),
    libraries=(
        LibraryDef(
            name="Leistungen Meldewesen & Ausweise",
            description="Leistungsbeschreibungen rund um Meldewesen und Ausweisdokumente.",
            source_type="HTTP_DIRECTORY",
            source_url="http://demo-corpus/leistungen-meldewesen-ausweise/",
            viewer_keys=("maria", "selin", "andrea"),
        ),
        LibraryDef(
            name="Leistungen Kfz-Zulassung",
            description="Leistungsbeschreibungen rund um Kfz-Zulassung und Führerschein.",
            source_type="HTTP_DIRECTORY",
            source_url="http://demo-corpus/leistungen-kfz-zulassung/",
            viewer_keys=("thomas", "andrea"),
        ),
        LibraryDef(
            name="Satzungen & Gebührenordnungen",
            description="Verwaltungsgebühren- und weitere städtische Satzungen mit Gebührentabellen.",
            source_type="HTTP_DIRECTORY",
            source_url="http://demo-corpus/satzungen-gebuehrenordnungen/",
            viewer_keys=("maria", "selin", "thomas", "andrea"),
        ),
        LibraryDef(
            name="Pressemitteilungen Stadt Rheinfurt",
            description="Pressemitteilungen der Stadt Rheinfurt (Sperrungen, Öffnungszeiten, Veranstaltungen).",
            source_type="RSS_FEED",
            source_url="http://presse.stadt-rheinfurt.example/rss.xml",
            viewer_keys=("maria", "selin", "thomas", "andrea"),
        ),
        LibraryDef(
            name="Interne Dienstanweisungen Meldewesen",
            description="Dienstanweisungen, Eskalationsregeln, interne FAQ und Schulungsfolien Meldewesen.",
            source_type="UPLOAD",
            viewer_keys=("maria", "selin", "andrea"),
            upload_dir=DEMO_CORPUS_ROOT / "interne-dienstanweisungen-meldewesen",
        ),
    ),
)

_E2E_ADMIN = UserDef(
    key="admin",
    display_name="Dev Admin",
    email="admin@opaa.local",
    identity="dev-admin",
)
_E2E_USER = UserDef(
    key="user",
    display_name="Dev User",
    email="dev-user@opaa.local",
    identity="dev-user",
)
_E2E_OUTSIDER = UserDef(
    key="outsider",
    display_name="Dev Outsider",
    email="outsider@opaa.local",
    identity="dev-outsider",
)

E2E_PROFILE = Profile(
    name="e2e",
    auth_mode="dev",
    admin=_E2E_ADMIN,
    # dev-outsider is provisioned (so listUsers/other assertions can rely on it existing) but is
    # deliberately given no space membership and no library grant - the negative case a permission
    # test needs, mirroring e2e's own existing "outsider" concept (#424).
    users=(_E2E_USER, _E2E_OUTSIDER),
    spaces=(
        SpaceDef(
            name="E2E Space",
            description="Minimaler Space des e2e-Datenprofils.",
            owner_key="user",
        ),
    ),
    libraries=(
        LibraryDef(
            name="E2E Wissensbibliothek",
            description="Minimale Upload-Bibliothek des e2e-Datenprofils.",
            source_type="UPLOAD",
            viewer_keys=("user",),
            upload_dir=E2E_SEED_UPLOAD_ROOT,
        ),
    ),
)

PROFILES: dict[str, Profile] = {
    DEMO_PROFILE.name: DEMO_PROFILE,
    E2E_PROFILE.name: E2E_PROFILE,
}
