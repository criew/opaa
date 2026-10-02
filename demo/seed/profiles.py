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
  Authenticates via the dev-auth header against the "dev-admin"/"dev-user"/"dev-outsider"/
  "dev-format-pipelines" accounts that stack already provisions (see docker-compose.e2e.yml's
  OPAA_AUTH_DEV_USERS_* block). Since
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

from collections.abc import Mapping
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DEMO_CORPUS_ROOT = REPO_ROOT / "demo" / "corpus"
E2E_DATA_ROOT = REPO_ROOT / "demo" / "seed" / "e2e-data"
E2E_SEED_UPLOAD_ROOT = E2E_DATA_ROOT / "test-documents" / "seed"
DEMO_CHATS_ROOT = REPO_ROOT / "demo" / "seed" / "chats"


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
    # Libraries (by LibraryDef.name) to associate with this space. A chat searches exactly what is
    # associated, so a space without an entry here searches nothing and shows the hint to assign
    # knowledge. The association is created through the owner's own session: associateSpaceAsset
    # requires CURATOR or above on the space plus at least VIEWER on the library, both of which
    # the owner has once the grants of steps 5 and 6 exist.
    library_names: tuple[str, ...] = field(default_factory=tuple)


@dataclass(frozen=True)
class PersonalSpaceDef:
    """The associations of an account's automatic personal space ("Meine Dokumente"), which the
    backend creates on the first login and the seed therefore finds instead of creating. Like any
    space it searches only what is associated; the owner is its ADMIN and must read every entry."""

    owner_key: str
    library_names: tuple[str, ...]
    prompt_library_names: tuple[str, ...] = field(default_factory=tuple)


@dataclass(frozen=True)
class LibraryDef:
    name: str
    description: str
    source_type: str  # source type key: HTTP_DIRECTORY, RSS_FEED, UPLOAD, S3
    viewer_keys: tuple[str, ...]
    source_url: str | None = None
    upload_dir: Path | None = None  # every file below is uploaded, subdirectories become folders
    # S3 only (ADR-0027): the static key as accessKey:secretKey (write-only in the API) and the
    # typed settings the API's S3Settings schema takes (pathStyle, scopes, patterns). Documented
    # demo values, never secrets - the bucket lives in the demo stack's own object store (docker-compose.yml).
    source_credentials: str | None = None
    s3_settings: Mapping[str, object] | None = None  # read-only by contract, like every field here
    # The corpus directory a connector run must reproduce one-to-one. seed.py counts its files and
    # fails a run that ended COMPLETED with fewer documents - without it, a run against a bucket
    # the "objectstore-seed" step has not finished filling reports success with nothing indexed.
    expected_documents_dir: Path | None = None


@dataclass(frozen=True)
class GroupDef:
    """An internal group (ADR-0036, Entscheidung 4): created through POST /v1/groups, which
    auto-appoints the caller (the admin account) as its first steward - ensure_group in seed.py
    hands that stewardship over to steward_keys and withdraws the admin's own, so the demo shows
    "benannte Verantwortliche" rather than the admin account itself."""

    name: str
    description: str
    steward_keys: tuple[str, ...]
    member_keys: tuple[str, ...] = field(default_factory=tuple)
    released_for_use: bool = True
    # Library names (LibraryDef.name) the group gets VIEWER on - deliberately never also listed in
    # that library's own viewer_keys, so a member reads it *exclusively* through the group.
    library_grants: tuple[str, ...] = field(default_factory=tuple)
    # (SpaceDef.name, SpaceRole) - the group becomes a member of that space, carrying the role to
    # every one of its own members without an individual space membership row of their own (#1815).
    space_membership: tuple[str, str] | None = None


@dataclass(frozen=True)
class ProviderGroupDef:
    """A group of the Keycloak realm (ADR-0036, Entscheidung 3). It reaches OPAA only through the
    directory sync, as an ORG_UNIT group of the provider; the seed never creates it or changes its
    members - member_keys states what the realm holds and only serves the rights matrix. Rights and
    space membership work as for GroupDef."""

    name: str
    member_keys: tuple[str, ...]
    library_grants: tuple[str, ...] = field(default_factory=tuple)
    space_membership: tuple[str, str] | None = None


@dataclass(frozen=True)
class DirectorySyncDef:
    """The Keycloak connector of the provider's directory sync: the confidential service-account
    client of keycloak/realm-export.json, holding view-users and query-groups. client_secret is the
    documented demo value and only the fallback for local and CI stacks; a reachable instance hands
    the seed its own secret (seed.py --directory-client-secret)."""

    client_id: str
    client_secret: str
    interval_minutes: int


@dataclass(frozen=True)
class PromptVariableDef:
    """One variable of a prompt (PromptVariable in prompts.yaml). The prompt text uses it as
    {{name}}; {{CURRENT_DATE}} and {{USER_NAME}} are system variables and never defined here."""

    name: str
    label: str
    type: str  # PromptVariableType: TEXT, TEXTAREA, SELECT, DATE
    required: bool = True
    default_value: str | None = None  # SELECT: one of options, DATE: yyyy-MM-dd
    options: tuple[str, ...] = field(default_factory=tuple)  # SELECT only


@dataclass(frozen=True)
class PromptDef:
    name: str  # the slash command without the slash: lower-case letters, digits, single hyphens
    title: str
    text: str
    description: str | None = None
    variables: tuple[PromptVariableDef, ...] = field(default_factory=tuple)
    sort_order: int = 0


@dataclass(frozen=True)
class PromptLibraryDef:
    """A prompt library, created through the owner's own session (every account holds
    CREATE_PROMPT_LIBRARY by default) and therefore owned by that person. Its reach is exactly
    all_accounts_viewer plus viewer_keys; space_names offers its prompts in the chats of those
    spaces - a chat offers no other - and grants nothing: each space owner must already be able to
    read the library."""

    name: str
    description: str
    owner_key: str
    prompts: tuple[PromptDef, ...]
    listed: bool = False
    all_accounts_viewer: bool = False
    viewer_keys: tuple[str, ...] = field(default_factory=tuple)
    space_names: tuple[str, ...] = field(default_factory=tuple)


@dataclass(frozen=True)
class Profile:
    name: str
    auth_mode: str  # "keycloak" or "dev"
    admin: UserDef
    users: tuple[UserDef, ...]
    spaces: tuple[SpaceDef, ...]
    libraries: tuple[LibraryDef, ...]
    groups: tuple[GroupDef, ...] = field(default_factory=tuple)
    prompt_libraries: tuple[PromptLibraryDef, ...] = field(default_factory=tuple)
    personal_spaces: tuple[PersonalSpaceDef, ...] = field(default_factory=tuple)
    provider_groups: tuple[ProviderGroupDef, ...] = field(default_factory=tuple)
    directory_sync: DirectorySyncDef | None = None
    # Directories of prepared chat transcripts (chats.py), imported after indexing so their
    # sources resolve.
    chat_sets: tuple[Path, ...] = field(default_factory=tuple)

    def all_users(self) -> tuple[UserDef, ...]:
        return (self.admin, *self.users)


DEMO_PASSWORD = "RheinfurtDemo!2026"  # nosec - documented demo credential, see demo/README.md
# nosec - documented demo credential of the service account opaa-directory, see demo/README.md
DEMO_DIRECTORY_CLIENT_SECRET = "RheinfurtVerzeichnis!2026"

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

# Prompt libraries of the demo. Both belong to Andrea (Amtsleitung): the Textbausteine reach every
# account through "Alle Konten" and are listed in the catalog; her own Vorlagen stay unlisted and
# without any further grant, so Maria, Selin and Thomas neither use nor find them. Every prompt asks
# for what the Rheinfurt corpus answers - Leistungen, Satzungen, Pressemitteilungen, Ratsinformationen.
_DEMO_PROMPT_LIBRARIES = (
    PromptLibraryDef(
        name="Textbausteine Bürgerbüro",
        description=(
            "Gemeinsame Formulierungshilfen des Bürgerbüros Rheinfurt für Bürgeranfragen, "
            "Gebührenauskünfte, Aktenvermerke und Pressemitteilungen. Gepflegt von der Amtsleitung."
        ),
        owner_key="andrea",
        listed=True,
        all_accounts_viewer=True,
        space_names=(
            "Meldewesen & Ausweise",
            "Maria Weber – persönlich",
            "Kfz-Zulassung",
            "Amtsleitung Bürgerbüro",
            "Dienstbesprechung Bürgerbüro",
        ),
        prompts=(
            PromptDef(
                name="antwort-buergeranfrage",
                title="Antwort auf Bürgeranfrage",
                description="Antwortschreiben mit Unterlagen, Gebühren, Terminweg und Frist.",
                text=(
                    "Entwirf eine Antwort des Bürgerbüros Rheinfurt auf die folgende "
                    "Bürgeranfrage:\n\n{{anliegen}}\n\nStütze dich auf die Leistungsbeschreibungen, "
                    "Satzungen und Pressemitteilungen der Stadt Rheinfurt. Nenne die benötigten "
                    "Unterlagen, die anfallenden Gebühren und den Weg zu einem Termin im "
                    "Bürgerbüro. Weise darauf hin, dass fehlende Unterlagen {{frist}} "
                    "nachgereicht werden können. Schreibe {{tonfall}} und schließe mit „Mit "
                    "freundlichen Grüßen, {{USER_NAME}}, Bürgerbüro Rheinfurt“."
                ),
                variables=(
                    PromptVariableDef(
                        name="anliegen",
                        label="Anliegen der Bürgerin oder des Bürgers",
                        type="TEXTAREA",
                        default_value=(
                            "Ich ziehe nächsten Monat innerhalb von Rheinfurt um. Was muss ich für "
                            "die Ummeldung mitbringen, und kostet das etwas?"
                        ),
                    ),
                    PromptVariableDef(
                        name="frist",
                        label="Frist für fehlende Unterlagen",
                        type="SELECT",
                        default_value="innerhalb von zwei Wochen",
                        options=(
                            "innerhalb von zwei Wochen",
                            "innerhalb von vier Wochen",
                            "bis zum Termin im Bürgerbüro",
                        ),
                    ),
                    PromptVariableDef(
                        name="tonfall",
                        label="Tonfall",
                        type="SELECT",
                        default_value="sachlich und förmlich",
                        options=("sachlich und förmlich", "besonders einfach und verständlich"),
                    ),
                ),
                sort_order=10,
            ),
            PromptDef(
                name="gebuehrenauskunft-personalausweis",
                title="Gebührenauskunft Personalausweis",
                description="Gebühr, Gültigkeit und Unterlagen je Altersgruppe und Anlass.",
                text=(
                    "Welche Gebühr erhebt das Bürgerbüro Rheinfurt für einen Personalausweis in "
                    "folgendem Fall: antragstellende Person {{altersgruppe}}, Anlass: {{anlass}}? "
                    "Nenne den Betrag, die Gültigkeitsdauer des Ausweises und die mitzubringenden "
                    "Unterlagen und verweise auf die Leistungsbeschreibung bzw. die "
                    "Verwaltungsgebührensatzung der Stadt Rheinfurt. Formuliere die Auskunft so, "
                    "dass sie unmittelbar an die Bürgerin oder den Bürger gehen kann."
                ),
                variables=(
                    PromptVariableDef(
                        name="altersgruppe",
                        label="Alter der antragstellenden Person",
                        type="SELECT",
                        default_value="24 Jahre und älter",
                        options=("unter 24 Jahre", "24 Jahre und älter"),
                    ),
                    PromptVariableDef(
                        name="anlass",
                        label="Anlass",
                        type="SELECT",
                        default_value="Neuausstellung nach Ablauf der Gültigkeit",
                        options=(
                            "Erstausstellung",
                            "Neuausstellung nach Ablauf der Gültigkeit",
                            "Verlust oder Diebstahl",
                            "vorläufiger Personalausweis",
                        ),
                    ),
                ),
                sort_order=20,
            ),
            PromptDef(
                name="aktenvermerk",
                title="Vermerk für die Akte",
                description="Aktenvermerk mit Sachverhalt, Rechtsgrundlage und weiterem Vorgehen.",
                text=(
                    "Verfasse einen Aktenvermerk im Verwaltungsstil der Stadt Rheinfurt.\n\n"
                    "Aktenzeichen: {{aktenzeichen}}\nSachgebiet: {{sachgebiet}}\n"
                    "Datum: {{CURRENT_DATE}}\nBearbeitung: {{USER_NAME}}\n\n"
                    "Sachverhalt:\n{{sachverhalt}}\n\n"
                    "Gliedere den Vermerk in Sachverhalt, Rechtsgrundlage und weiteres Vorgehen. "
                    "Belege die Rechtsgrundlage mit den einschlägigen Leistungsbeschreibungen, "
                    "Satzungen oder Dienstanweisungen und nenne keine Vorschrift, die in den "
                    "Quellen nicht vorkommt."
                ),
                variables=(
                    PromptVariableDef(name="aktenzeichen", label="Aktenzeichen", type="TEXT"),
                    PromptVariableDef(
                        name="sachgebiet",
                        label="Sachgebiet",
                        type="SELECT",
                        default_value="Meldewesen & Ausweise",
                        options=(
                            "Meldewesen & Ausweise",
                            "Kfz-Zulassung",
                            "Amtsleitung Bürgerbüro",
                        ),
                    ),
                    PromptVariableDef(name="sachverhalt", label="Sachverhalt", type="TEXTAREA"),
                ),
                sort_order=30,
            ),
            PromptDef(
                name="pressemitteilung-ratsbeschluss",
                title="Pressemitteilung aus Ratsbeschluss",
                description="Pressemitteilung aus Niederschrift oder Beschlussvorlage eines Gremiums.",
                text=(
                    "Suche in den Ratsinformationen der Stadt Rheinfurt den Beschluss zum Thema "
                    "„{{thema}}“ ({{gremium}}, Sitzung vom {{sitzungsdatum}}) und formuliere daraus "
                    "eine Pressemitteilung der Stadt Rheinfurt im Stil der bisherigen "
                    "Pressemitteilungen: Überschrift, kurzer Vorspann, Inhalt des Beschlusses mit "
                    "Abstimmungsergebnis und was er für die Bürgerinnen und Bürger bedeutet. "
                    "Erfinde keine Zitate und keine Zahlen, die nicht in der Niederschrift oder "
                    "Beschlussvorlage stehen."
                ),
                variables=(
                    PromptVariableDef(
                        name="thema",
                        label="Thema des Beschlusses",
                        type="TEXT",
                        default_value="Mobiles Bürgerbüro",
                    ),
                    PromptVariableDef(
                        name="gremium",
                        label="Gremium",
                        type="SELECT",
                        default_value="Hauptausschuss",
                        options=("Stadtrat", "Hauptausschuss"),
                    ),
                    PromptVariableDef(
                        name="sitzungsdatum",
                        label="Sitzungsdatum",
                        type="DATE",
                        default_value="2026-04-21",
                    ),
                ),
                sort_order=40,
            ),
        ),
    ),
    PromptLibraryDef(
        name="Vorlagen Amtsleitung",
        description="Persönliche Vorlagen der Amtsleitung Bürgerbüro für Berichte und Gremienarbeit.",
        owner_key="andrea",
        space_names=("Amtsleitung Bürgerbüro",),
        prompts=(
            PromptDef(
                name="wochenbericht-dezernentin",
                title="Wochenbericht an die Dezernentin",
                description="Wochenbericht aus Ratsbeschlüssen, Pressemitteilungen und Dienstanweisungen.",
                text=(
                    "Erstelle den Wochenbericht der Amtsleitung Bürgerbüro Rheinfurt an die "
                    "Dezernentin für {{kalenderwoche}}. Gliedere ihn in:\n"
                    "1. Beschlüsse von Stadtrat und Hauptausschuss mit Bezug zum Bürgerbüro (etwa "
                    "Stellenplan, mobiles Bürgerbüro, Terminvergabe) und ihr Umsetzungsstand,\n"
                    "2. geänderte Öffnungszeiten, Schließtage und Sperrungen laut den "
                    "Pressemitteilungen der Stadt,\n"
                    "3. Hinweise aus den internen Dienstanweisungen, die für den Schalterbetrieb "
                    "gerade wichtig sind,\n"
                    "4. Schwerpunkt der Woche: {{schwerpunkt}}.\n"
                    "Belege jede Aussage mit ihrer Quelle, halte den Bericht auf höchstens einer "
                    "Seite und schließe mit „Stand {{CURRENT_DATE}}, {{USER_NAME}}, Amtsleitung "
                    "Bürgerbüro“."
                ),
                variables=(
                    PromptVariableDef(
                        name="kalenderwoche", label="Kalenderwoche (z. B. KW 40/2026)", type="TEXT"
                    ),
                    PromptVariableDef(
                        name="schwerpunkt",
                        label="Schwerpunkt der Woche",
                        type="TEXTAREA",
                        required=False,
                        default_value="Wartezeiten auf einen Termin im Meldewesen",
                    ),
                ),
                sort_order=10,
            ),
            PromptDef(
                name="stellungnahme-hauptausschuss",
                title="Stellungnahme für den Hauptausschuss",
                description="Stellungnahme der Amtsleitung zu einer Vorlage, mit Beschlussempfehlung.",
                text=(
                    "Entwirf eine Stellungnahme der Amtsleitung Bürgerbüro zur Vorlage "
                    "„{{vorlage}}“ für die Sitzung des Hauptausschusses am {{sitzungstermin}}. "
                    "Grundhaltung: {{haltung}}.\n\n"
                    "Fasse zuerst den Inhalt der Vorlage und die bisherigen Beschlüsse von "
                    "Stadtrat und Hauptausschuss zum selben Thema zusammen. Benenne dann die "
                    "Auswirkungen auf Personal, Wartezeiten und Gebühren im Bürgerbüro und schließe "
                    "mit einer Beschlussempfehlung. Stütze dich auf die Ratsinformationen, Satzungen "
                    "und Leistungsbeschreibungen der Stadt Rheinfurt und kennzeichne, wo die "
                    "Quellen keine Aussage treffen."
                ),
                variables=(
                    PromptVariableDef(
                        name="vorlage",
                        label="Vorlage",
                        type="TEXT",
                        default_value=(
                            "Beschlussvorlage 2024/019: Anschaffung eines Bürgerkoffers für die "
                            "mobile Beratung in Pflegeeinrichtungen"
                        ),
                    ),
                    PromptVariableDef(
                        name="sitzungstermin",
                        label="Sitzungstermin",
                        type="DATE",
                        default_value="2024-05-14",
                    ),
                    PromptVariableDef(
                        name="haltung",
                        label="Grundhaltung der Amtsleitung",
                        type="SELECT",
                        default_value="zustimmend mit Ergänzungen",
                        options=("zustimmend", "zustimmend mit Ergänzungen", "ablehnend"),
                    ),
                ),
                sort_order=20,
            ),
        ),
    ),
)

# The knowledge of each Sachgebiet - what its space and the personal spaces of its accounts carry.
_MELDEWESEN_LIBRARIES = (
    "Leistungen Meldewesen & Ausweise",
    "Satzungen & Gebührenordnungen",
    "Pressemitteilungen Stadt Rheinfurt",
    "Interne Dienstanweisungen Meldewesen",
    "Ratsinformationen Stadt Rheinfurt",
)
_KFZ_LIBRARIES = (
    "Leistungen Kfz-Zulassung",
    "Satzungen & Gebührenordnungen",
    "Pressemitteilungen Stadt Rheinfurt",
    "Ratsinformationen Stadt Rheinfurt",
)
_AMTSLEITUNG_LIBRARIES = (
    "Leistungen Meldewesen & Ausweise",
    "Leistungen Kfz-Zulassung",
    "Satzungen & Gebührenordnungen",
    "Pressemitteilungen Stadt Rheinfurt",
    "Interne Dienstanweisungen Meldewesen",
    "Ratsinformationen Stadt Rheinfurt",
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
            # Selin joins through the Keycloak group "Meldewesen" (provider_groups below).
            library_names=_MELDEWESEN_LIBRARIES,
        ),
        SpaceDef(
            name="Maria Weber – persönlich",
            description="Persönlicher Arbeitsraum von Maria Weber, kein weiteres Mitglied.",
            owner_key="maria",
            library_names=_MELDEWESEN_LIBRARIES,
        ),
        SpaceDef(
            name="Kfz-Zulassung",
            description="Space des Sachgebiets Kfz-Zulassung.",
            owner_key="thomas",
            library_names=_KFZ_LIBRARIES,
        ),
        SpaceDef(
            name="Amtsleitung Bürgerbüro",
            description="Space der Amtsleitung des Bürgerbüros Rheinfurt.",
            owner_key="andrea",
            library_names=_AMTSLEITUNG_LIBRARIES,
        ),
        # Its only individual member is Andrea as owner; Maria, Selin and Thomas join exclusively
        # through the group "Sachbearbeitung Bürgerbüro" (step 6). It carries only the libraries
        # every fach account reads, so a question asked here never reaches past an account's rights.
        SpaceDef(
            name="Dienstbesprechung Bürgerbüro",
            description=(
                "Gemeinsamer Space von Amtsleitung und Sachbearbeitung des Bürgerbüros für "
                "sachgebietsübergreifende Themen der wöchentlichen Dienstbesprechung."
            ),
            owner_key="andrea",
            library_names=(
                "Satzungen & Gebührenordnungen",
                "Pressemitteilungen Stadt Rheinfurt",
                "Ratsinformationen Stadt Rheinfurt",
            ),
        ),
    ),
    libraries=(
        LibraryDef(
            name="Leistungen Meldewesen & Ausweise",
            description="Leistungsbeschreibungen rund um Meldewesen und Ausweisdokumente, dazu die Sprechtage des mobilen Bürgerbüros.",
            source_type="HTTP_DIRECTORY",
            source_url="http://demo-corpus/leistungen-meldewesen-ausweise/",
            # Maria and Selin read these through the Keycloak group "Meldewesen".
            viewer_keys=("andrea",),
        ),
        LibraryDef(
            name="Leistungen Kfz-Zulassung",
            description="Leistungsbeschreibungen rund um Kfz-Zulassung und Führerschein.",
            source_type="HTTP_DIRECTORY",
            source_url="http://demo-corpus/leistungen-kfz-zulassung/",
            # Thomas reads these through the Keycloak group "Kfz-Zulassung".
            viewer_keys=("andrea",),
        ),
        LibraryDef(
            name="Satzungen & Gebührenordnungen",
            description="Verwaltungsgebühren- und weitere städtische Satzungen mit Gebührentabellen.",
            source_type="HTTP_DIRECTORY",
            source_url="http://demo-corpus/satzungen-gebuehrenordnungen/",
            # Every fach account reads these through the Keycloak group "Bürgerbüro Rheinfurt".
            viewer_keys=(),
        ),
        LibraryDef(
            name="Pressemitteilungen Stadt Rheinfurt",
            description="Pressemitteilungen der Stadt Rheinfurt (Sperrungen, Öffnungszeiten, Veranstaltungen).",
            source_type="RSS_FEED",
            source_url="http://presse.stadt-rheinfurt.example/rss.xml",
            # Selin and Thomas read these only through the group "Presseverteiler Bürgerbüro".
            viewer_keys=("maria", "andrea"),
        ),
        LibraryDef(
            name="Interne Dienstanweisungen Meldewesen",
            description="Dienstanweisungen, Eskalationsregeln, interne FAQ, Schulungsfolien und Rundschreiben Meldewesen.",
            source_type="UPLOAD",
            viewer_keys=("maria", "selin", "andrea"),
            upload_dir=DEMO_CORPUS_ROOT / "interne-dienstanweisungen-meldewesen",
        ),
        # The S3 library (#1383, ADR-0027): reads the demo stack's object store (service "objectstore",
        # path-style over the Compose network), bucket "rheinfurt-archiv" under the prefix the
        # "objectstore-seed" init step mirrors demo/corpus/ratsinformationen/ to. Public council
        # information, readable by every fach account like the press releases.
        LibraryDef(
            name="Ratsinformationen Stadt Rheinfurt",
            description="Niederschriften und Beschlussvorlagen des Stadtrats, des Hauptausschusses und des Bauausschusses, nach Jahrgang und Gremium abgelegt; Vorlagen mit Anlagen liegen als Versandmail mit PDF-Anhängen vor.",
            source_type="S3",
            source_url="http://objectstore:9000",
            source_credentials="rheinfurt-archiv:RheinfurtDemo!2026",  # nosec - documented demo credential
            s3_settings={
                "pathStyle": True,
                "scopes": [{"bucket": "rheinfurt-archiv", "prefix": "ratsinformationen/"}],
            },
            # Every fach account reads these through the Keycloak group "Bürgerbüro Rheinfurt".
            viewer_keys=(),
            expected_documents_dir=DEMO_CORPUS_ROOT / "ratsinformationen",
        ),
        # The seventh library (#1520): a technical showcase, not a Fachablage - one document per
        # file extension OPAA admits, read over the S3 connector from the bucket "formattest" of
        # the same store. It stays with the admin account that creates it and gets no VIEWER grant;
        # only the admin's own personal space carries it, so it never widens what a fach account
        # sees.
        LibraryDef(
            name="Formattest auf S3",
            description=(
                "Technische Schaubibliothek: je ein Dokument pro unterstütztem Dateiformat, aus dem "
                "Objektspeicher des Demo-Stacks. Alle Dokumente sind synthetisch und im "
                "Rheinfurt-Kontext verfasst — bis auf die Outlook-Nachricht (.msg): Dieses Format "
                "lässt sich nicht erzeugen, die Datei stammt deshalb unverändert aus dem Testkorpus "
                "des Apache-POI-Projekts (Apache License 2.0) und ist als einzige englisch."
            ),
            source_type="S3",
            source_url="http://objectstore:9000",
            source_credentials="rheinfurt-archiv:RheinfurtDemo!2026",  # nosec - documented demo credential
            s3_settings={
                "pathStyle": True,
                "scopes": [{"bucket": "formattest"}],
            },
            viewer_keys=(),
            expected_documents_dir=DEMO_CORPUS_ROOT / "formate",
        ),
    ),
    groups=(
        # The internal-group scenario of ADR-0036, Entscheidung 4/9 and #1823's acceptance
        # criteria: Maria is the named steward (not the admin account, see GroupDef's own
        # docstring), Thomas is the only member, and both his library read and his space role in
        # "Meldewesen & Ausweise" flow *exclusively* through this group - he is not also a member
        # of the space and not also on "Interne Dienstanweisungen Meldewesen"'s own viewer_keys.
        GroupDef(
            name="Vertretung Meldewesen",
            description=(
                "Vertretungsfälle im Sachgebiet Meldewesen: Maria pflegt Mitglieder und Freigabe "
                "der Gruppe selbst (ADR-0036)."
            ),
            steward_keys=("maria",),
            member_keys=("thomas",),
            released_for_use=True,
            library_grants=("Interne Dienstanweisungen Meldewesen",),
            space_membership=("Meldewesen & Ausweise", "MEMBER"),
        ),
        # Selin and Thomas hold no VIEWER of their own on the press releases; Andrea stewards the
        # group but keeps her direct grant, as she is not a member.
        GroupDef(
            name="Presseverteiler Bürgerbüro",
            description=(
                "Verteiler des Bürgerbüros für die Pressemitteilungen des Presseamts der Stadt "
                "Rheinfurt: Mitglieder erhalten die Pressemitteilungen über diese Gruppe."
            ),
            steward_keys=("andrea",),
            member_keys=("selin", "thomas"),
            released_for_use=True,
            library_grants=("Pressemitteilungen Stadt Rheinfurt",),
        ),
        # Brings all three Sachbearbeitung accounts into "Dienstbesprechung Bürgerbüro" at once,
        # none of them with a membership row of their own; grants no library read.
        GroupDef(
            name="Sachbearbeitung Bürgerbüro",
            description=(
                "Alle Sachbearbeiterinnen und Sachbearbeiter des Bürgerbüros aus Meldewesen und "
                "Kfz-Zulassung."
            ),
            steward_keys=("andrea",),
            member_keys=("maria", "selin", "thomas"),
            released_for_use=True,
            space_membership=("Dienstbesprechung Bürgerbüro", "MEMBER"),
        ),
    ),
    prompt_libraries=_DEMO_PROMPT_LIBRARIES,
    # Every personal space carries knowledge and the shared Textbausteine: a fach account's the
    # knowledge of its Sachgebiet, the admin's the technical showcase only it reads. The hint for a
    # space without knowledge shows in any newly created space (Drehbuch, Schritt G).
    personal_spaces=(
        PersonalSpaceDef(
            owner_key="admin",
            library_names=("Formattest auf S3",),
            prompt_library_names=("Textbausteine Bürgerbüro",),
        ),
        PersonalSpaceDef(
            owner_key="maria",
            library_names=_MELDEWESEN_LIBRARIES,
            prompt_library_names=("Textbausteine Bürgerbüro",),
        ),
        PersonalSpaceDef(
            owner_key="selin",
            library_names=_MELDEWESEN_LIBRARIES,
            prompt_library_names=("Textbausteine Bürgerbüro",),
        ),
        # Thomas reads the internal instructions as Vertretung Meldewesen; his own space carries
        # them, his Sachgebiet's space "Kfz-Zulassung" does not (Drehbuch, Frage 5).
        PersonalSpaceDef(
            owner_key="thomas",
            library_names=(*_KFZ_LIBRARIES, "Interne Dienstanweisungen Meldewesen"),
            prompt_library_names=("Textbausteine Bürgerbüro",),
        ),
        PersonalSpaceDef(
            owner_key="andrea",
            library_names=_AMTSLEITUNG_LIBRARIES,
            prompt_library_names=("Textbausteine Bürgerbüro", "Vorlagen Amtsleitung"),
        ),
    ),
    # The three groups of keycloak/realm-export.json, brought in by the directory sync. Each one
    # carries the rights of its Sachgebiet on its own: no member also holds them directly.
    provider_groups=(
        ProviderGroupDef(
            name="Bürgerbüro Rheinfurt",
            member_keys=("admin", "maria", "selin", "thomas", "andrea"),
            library_grants=("Satzungen & Gebührenordnungen", "Ratsinformationen Stadt Rheinfurt"),
        ),
        ProviderGroupDef(
            name="Meldewesen",
            member_keys=("maria", "selin"),
            library_grants=("Leistungen Meldewesen & Ausweise",),
            space_membership=("Meldewesen & Ausweise", "MEMBER"),
        ),
        ProviderGroupDef(
            name="Kfz-Zulassung",
            member_keys=("thomas",),
            library_grants=("Leistungen Kfz-Zulassung",),
        ),
    ),
    directory_sync=DirectorySyncDef(
        client_id="opaa-directory",
        client_secret=DEMO_DIRECTORY_CLIENT_SECRET,
        interval_minutes=60,
    ),
    chat_sets=(DEMO_CHATS_ROOT / "amtsleitung-buergerbuero",),
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
            library_names=("E2E Wissensbibliothek",),
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
