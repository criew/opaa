import type {
  AccessBasis,
  AssetRole,
  AssetType,
  Capability,
  GroupMechanism,
  GroupOrigin,
  GroupProviderResponse,
  DatePrecision,
  DocumentSourceType,
  DocumentStatus,
  GroupKind,
  IndexingRunEventCategory,
  AssetVisibility,
  MetadataOrigin,
  PermissionSubjectType,
  ScheduleFrequency,
  ScheduleWeekday,
  SpaceRole,
  SpaceVisibility,
  ConfluenceEdition,
  IndexingRunMode,
  IndexingTriggerSource,
} from '../types/api'
import type { AccessLevel } from '../types/chat'

const spaceRoleLabels: Record<SpaceRole, string> = {
  MEMBER: 'Mitglied',
  CURATOR: 'Kurator',
  ADMIN: 'Administrator',
}

export function spaceRoleLabel(role: SpaceRole | string | undefined): string {
  if (!role) return ''
  return spaceRoleLabels[role as SpaceRole] ?? role
}

// #272: mirrors the three-row table in docs/features/spaces-and-assets.md#space-sichtbarkeit -
// PRIVATE is the default for every newly created space. The order here is also the order both
// SpaceCreatePage and SpaceSettingsPage render their Select options in - a single source
// keeps the two menus from drifting apart if a future enum value is added.
export const spaceVisibilities: SpaceVisibility[] = ['PRIVATE', 'DISCOVERABLE', 'OPEN']

const spaceVisibilityLabels: Record<SpaceVisibility, string> = {
  PRIVATE: 'Privat',
  DISCOVERABLE: 'Auffindbar',
  OPEN: 'Offen',
}

export function spaceVisibilityLabel(visibility: SpaceVisibility | string | undefined): string {
  if (!visibility) return ''
  return spaceVisibilityLabels[visibility as SpaceVisibility] ?? visibility
}

// #671 review: DISCOVERABLE/OPEN must not claim a directory or self-join already exist - neither
// SpaceService nor opaa-api.yaml has a directory or join endpoint yet (#272 is UI wiring only,
// see the issue's own "Einordnung" section). These describe the intended future meaning of each
// stage without promising a present-tense effect.
const spaceVisibilityDescriptions: Record<SpaceVisibility, string> = {
  PRIVATE:
    'Nur Mitglieder wissen, dass dieser Space existiert. Voreinstellung für jeden neu angelegten Space.',
  DISCOVERABLE:
    'Vorgesehen für das künftige Space-Verzeichnis: dort sichtbar, Beitritt auf Antrag. Verzeichnis und Beitritt kommen mit einem der Folge-Issues.',
  OPEN: 'Vorgesehen für das künftige Space-Verzeichnis: dort sichtbar, Selbstbeitritt mit einem Klick. Verzeichnis und Beitritt kommen mit einem der Folge-Issues.',
}

export function spaceVisibilityDescription(
  visibility: SpaceVisibility | string | undefined,
): string {
  if (!visibility) return ''
  return spaceVisibilityDescriptions[visibility as SpaceVisibility] ?? ''
}

const groupKindLabels: Record<GroupKind, string> = {
  ORG_UNIT: 'Organisationseinheit',
  AD_HOC: 'Ad-hoc-Gruppe',
  IDENTITY_PROVIDER: 'Gruppe aus dem Identitätsanbieter',
}

export function groupKindLabel(kind: GroupKind | string | undefined): string {
  if (!kind) return ''
  return groupKindLabels[kind as GroupKind] ?? kind
}

const accessLevelLabels: Record<AccessLevel, string> = {
  Public: 'Öffentlich',
  Internal: 'Intern',
  Confidential: 'Vertraulich',
}

export function accessLevelLabel(level: AccessLevel): string {
  return accessLevelLabels[level]
}

const libraryVisibilityLabels: Record<AssetVisibility, string> = {
  PRIVATE: 'privat',
  SHARED: 'geteilt',
  ORGANIZATION: 'organisationsweit',
}

export function libraryVisibilityLabel(visibility: AssetVisibility | string | undefined): string {
  if (!visibility) return ''
  return libraryVisibilityLabels[visibility as AssetVisibility] ?? visibility
}

/** Render order of the distribution levels in LibraryCreatePage and LibraryDetailPage. */
export const libraryVisibilities = Object.keys(libraryVisibilityLabels) as AssetVisibility[]

// One sentence per distribution level, following the semantics documented on the
// AssetVisibility schema in opaa-api.yaml and docs/features/spaces-and-assets.md.
const libraryVisibilityDescriptions: Record<AssetVisibility, string> = {
  PRIVATE: 'Nur der Eigentümer nutzt den Bestand — bei Gruppen-Eigentum die Mitglieder der Gruppe.',
  SHARED: 'Die Reichweite bestimmen die Freigaben an Personen und Gruppen.',
  ORGANIZATION: 'Lesbar für alle Nutzer der Organisation.',
}

export function libraryVisibilityDescription(
  visibility: AssetVisibility | string | undefined,
): string {
  if (!visibility) return ''
  return libraryVisibilityDescriptions[visibility as AssetVisibility] ?? ''
}

const assetRoleLabels: Record<AssetRole, string> = {
  VIEWER: 'Betrachter',
  EDITOR: 'Bearbeiter',
  MANAGER: 'Verwalter',
  OWNER: 'Eigentümer',
}

export function assetRoleLabel(role: AssetRole | string | undefined): string {
  if (!role) return ''
  return assetRoleLabels[role as AssetRole] ?? role
}

// One sentence per role, mirroring the graded ranking documented on the AssetRole schema in
// opaa-api.yaml (VIEWER < EDITOR < MANAGER < OWNER, deliberately separate from SpaceRole) - each
// additionally implies everything the role below it already permits, so every sentence below
// starts with "zusätzlich" except VIEWER's, which is the baseline a grant can carry.
const assetRoleDescriptions: Record<AssetRole, string> = {
  VIEWER: 'Darf die Bibliothek benutzen und ihren Inhalt einsehen.',
  EDITOR: 'Darf zusätzlich Dokumente ändern, hochladen und entfernen.',
  MANAGER: 'Darf zusätzlich Rechte vergeben und die Sichtbarkeit der Bibliothek ändern.',
  OWNER: 'Darf zusätzlich die Bibliothek löschen und das Eigentum übertragen.',
}

export function assetRoleDescription(role: AssetRole | string | undefined): string {
  if (!role) return ''
  return assetRoleDescriptions[role as AssetRole] ?? ''
}

const assetTypeLabels: Record<AssetType, string> = {
  KNOWLEDGE_LIBRARY: 'Bibliothek',
}

/** The singular noun of an asset type, as a sentence names it ("diese Bibliothek"). */
export function assetTypeLabel(assetType: AssetType | string | undefined): string {
  if (!assetType) return ''
  return assetTypeLabels[assetType as AssetType] ?? assetType
}

const assetGrantScopeHints: Record<AssetType, string> = {
  KNOWLEDGE_LIBRARY:
    'Eine Freigabe gewährt Zugriff auf alle Dokumente dieser Bibliothek, nicht auf eine Auswahl.',
}

/** What one grant opens on an asset of this type - the first sentence of the rights dialog. */
export function assetGrantScopeHint(assetType: AssetType): string {
  return assetGrantScopeHints[assetType] ?? ''
}

const permissionSubjectTypeLabels: Record<PermissionSubjectType, string> = {
  USER: 'Person',
  GROUP: 'Gruppe',
}

export function permissionSubjectTypeLabel(
  subjectType: PermissionSubjectType | string | undefined,
): string {
  if (!subjectType) return ''
  return permissionSubjectTypeLabels[subjectType as PermissionSubjectType] ?? subjectType
}

const documentStatusLabels: Record<DocumentStatus, string> = {
  PENDING: 'wird verarbeitet',
  INDEXED: 'indiziert',
  FAILED: 'fehlgeschlagen',
}

export function documentStatusLabel(status: DocumentStatus | string | undefined): string {
  if (!status) return ''
  return documentStatusLabels[status as DocumentStatus] ?? status
}

const documentSourceTypeLabels: Record<DocumentSourceType, string> = {
  UPLOAD: 'Upload',
  FILESYSTEM: 'Dateisystem',
  HTTP_DIRECTORY: 'Webverzeichnis',
  RSS_FEED: 'RSS-Feed',
  CONFLUENCE: 'Confluence',
  S3: 'S3-Objektspeicher',
}

export function documentSourceTypeLabel(
  sourceType: DocumentSourceType | string | undefined,
): string {
  if (!sourceType) return ''
  return documentSourceTypeLabels[sourceType as DocumentSourceType] ?? sourceType
}

// One sentence per source type, shown on the origin cards in LibraryCreatePage (mockup 1e wording).
const documentSourceTypeDescriptions: Record<DocumentSourceType, string> = {
  UPLOAD: 'Dateien auswählen oder hineinziehen; einzelne Dokumente pflegen.',
  FILESYSTEM: 'Ein Pfad im Hausnetz wird regelmäßig eingelesen.',
  HTTP_DIRECTORY: 'Eine interne Webadresse wird durchlaufen und indiziert.',
  RSS_FEED: 'Neue Beiträge werden laufend übernommen, Anhänge wahlweise.',
  CONFLUENCE: 'Ausgewählte Spaces eines Confluence (Cloud oder Data Center) werden eingelesen.',
  S3: 'Buckets und Präfixe eines S3-kompatiblen Objektspeichers werden eingelesen.',
}

export function documentSourceTypeDescription(
  sourceType: DocumentSourceType | string | undefined,
): string {
  if (!sourceType) return ''
  return documentSourceTypeDescriptions[sourceType as DocumentSourceType] ?? 'Weiterer Quellentyp.'
}

// Derived from documentSourceTypeLabels rather than written out again, so it stays in sync with
// that Record<DocumentSourceType, string> - which itself is exhaustive over the generated
// DocumentSourceType union at compile time: TypeScript rejects the file if a new enum value (like
// a future connector type) is added to the OpenAPI spec without also giving it a label here.
// openapi-typescript erases enums to a type-only union - there is no runtime array to import
// straight from the generated spec types - so this is the closest a purely frontend change gets
// to "the template list follows the spec automatically" without a build-time codegen step.
export const allDocumentSourceTypes = Object.keys(documentSourceTypeLabels) as DocumentSourceType[]

/**
 * Which configuration fields LibraryCreatePage renders and validates for each source type,
 * mirroring KnowledgeLibraryService#validateConfigurationForType (ADR-0018):
 * - 'none': no source configuration fields are shown/sent (UPLOAD).
 * - 'path': a required, server-absolute directory path (FILESYSTEM).
 * - 'url': a required http(s) URL plus optional proxy/credentials/insecure-SSL (HTTP_DIRECTORY,
 *   RSS_FEED - both run-based, URL-fetched source types with the identical configuration shape).
 * - 'confluence': base address, edition-dependent credentials and a space selection (CONFLUENCE,
 *   ADR-0023) - its own multi-stage flow, see LibraryCreatePage.
 * - 's3': endpoint, region and addressing style from a provider template, a static key and one to
 *   fifty scopes (S3, ADR-0027) - its own staged form, see S3SourceForm.
 *
 * Just like documentSourceTypeLabels, this is a Record over the full DocumentSourceType union, so
 * a future enum value forces a compile error here instead of silently rendering as a template with
 * no configuration fields at all.
 */
export type DocumentSourceConfigKind = 'none' | 'path' | 'url' | 'confluence' | 's3'

export const documentSourceTypeConfigKind: Record<DocumentSourceType, DocumentSourceConfigKind> = {
  UPLOAD: 'none',
  FILESYSTEM: 'path',
  HTTP_DIRECTORY: 'url',
  RSS_FEED: 'url',
  CONFLUENCE: 'confluence',
  S3: 's3',
}

// #513: German, understandable categories for a skipped/rejected item or error in a run's
// protocol - matches io.opaa.indexing.job.IndexingEventCategory's own Javadoc one-to-one.
const indexingRunEventCategoryLabels: Record<IndexingRunEventCategory, string> = {
  REJECTED: 'Abgewiesen',
  UNREACHABLE: 'Nicht erreichbar',
  UNSUPPORTED_FORMAT: 'Format nicht unterstützt',
  ALLOWLIST: 'Allowlist',
  ERROR: 'Fehler',
  // #404: indexed anyway, only the deviation between the file's own extension and its detected
  // content is reported here.
  FORMAT_MISMATCH: 'Endung weicht vom Inhalt ab',
  SCHEDULE_SKIPPED: 'Geplanter Lauf übersprungen',
  // #886: the document no longer exists at its source and was removed at the end of a
  // successful, complete run - a note about the removal, not a skip/reject/error of this run.
  REMOVED: 'In der Quelle entfernt',
  // #1136: the source throttled the run and it slowed down instead of failing - one summary note.
  RATE_LIMITED: 'Ratenbegrenzung',
  BUDGET_EXHAUSTED: 'Anfragebudget erschöpft',
  // #1380: the run's own figures, one note per run for a source that counts them.
  SUMMARY: 'Kennzahlen',
}

// ADR-0023, Entscheidung 4 (#1136): the Betriebsart of a run - whether its listing was complete
// (and could remove what it did not meet again) or only picked up changes.
const indexingRunModeLabels: Record<IndexingRunMode, string> = {
  FULL: 'Vollabgleich',
  INCREMENTAL: 'Inkrementell',
  // ADR-0027, Entscheidung 3 (#1381): the run an S3 event notification starts - reported keys
  // checked one by one, never a listing.
  EVENT: 'Ereignislauf',
}

export function indexingRunModeLabel(mode: IndexingRunMode | string | undefined): string {
  if (!mode) return ''
  return indexingRunModeLabels[mode as IndexingRunMode] ?? mode
}

const indexingTriggerSourceLabels: Record<IndexingTriggerSource, string> = {
  MANUAL: 'manuell gestartet',
  SCHEDULED: 'per Zeitplan',
  WEBHOOK: 'per Webhook',
}

/** Who started a run (#485, #1140) - shown in the run history beside the run mode. */
export function indexingTriggerSourceLabel(
  source: IndexingTriggerSource | string | undefined,
): string {
  if (!source) return ''
  return indexingTriggerSourceLabels[source as IndexingTriggerSource] ?? source
}

export function indexingRunEventCategoryLabel(
  category: IndexingRunEventCategory | string | undefined,
): string {
  if (!category) return ''
  return indexingRunEventCategoryLabels[category as IndexingRunEventCategory] ?? category
}

// #485: feste Intervallstufen für den Bibliotheks-Zeitplan - die Reihenfolge ist auch die
// Optionsreihenfolge in EditLibraryScheduleDialog.
export const scheduleFrequencies: ScheduleFrequency[] = ['DISABLED', 'HOURLY', 'DAILY', 'WEEKLY']

const scheduleFrequencyLabels: Record<ScheduleFrequency, string> = {
  DISABLED: 'Aus',
  HOURLY: 'Stündlich',
  DAILY: 'Täglich',
  WEEKLY: 'Wöchentlich',
}

export function scheduleFrequencyLabel(frequency: ScheduleFrequency | string | undefined): string {
  if (!frequency) return ''
  return scheduleFrequencyLabels[frequency as ScheduleFrequency] ?? frequency
}

export const scheduleWeekdays: ScheduleWeekday[] = [
  'MONDAY',
  'TUESDAY',
  'WEDNESDAY',
  'THURSDAY',
  'FRIDAY',
  'SATURDAY',
  'SUNDAY',
]

const scheduleWeekdayLabels: Record<ScheduleWeekday, string> = {
  MONDAY: 'Montag',
  TUESDAY: 'Dienstag',
  WEDNESDAY: 'Mittwoch',
  THURSDAY: 'Donnerstag',
  FRIDAY: 'Freitag',
  SATURDAY: 'Samstag',
  SUNDAY: 'Sonntag',
}

export function scheduleWeekdayLabel(weekday: ScheduleWeekday | string | undefined): string {
  if (!weekday) return ''
  return scheduleWeekdayLabels[weekday as ScheduleWeekday] ?? weekday
}

/** Formats a byte count as a German-locale size string (e.g. "1,2 MB"), or an em dash if unknown. */
export function formatFileSize(bytes: number | null | undefined): string {
  if (bytes == null) return '—'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let value = bytes / 1024
  let unitIndex = 0
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024
    unitIndex += 1
  }
  return `${value.toLocaleString('de-DE', { maximumFractionDigits: 1 })} ${units[unitIndex]}`
}

const confluenceEditionLabels: Record<ConfluenceEdition, string> = {
  CLOUD: 'Cloud',
  DATA_CENTER: 'Data Center',
}

export function confluenceEditionLabel(edition: ConfluenceEdition | string | undefined): string {
  if (!edition) return ''
  return confluenceEditionLabels[edition as ConfluenceEdition] ?? edition
}

// #1068: provenance of a document metadata value (metadata-schema.md, "Jeder Wert trägt seine
// Herkunft") - a DERIVED value is always marked as such in the UI.
const metadataOriginLabels: Record<MetadataOrigin, string> = {
  DETERMINISTIC: 'automatisch ermittelt',
  DERIVED: 'abgeleitet',
  MANUAL: 'manuell',
}

export function metadataOriginLabel(origin: MetadataOrigin | string | null | undefined): string {
  if (!origin) return ''
  return metadataOriginLabels[origin as MetadataOrigin] ?? origin
}

export const datePrecisions: DatePrecision[] = ['DAY', 'MONTH', 'YEAR']

const datePrecisionLabels: Record<DatePrecision, string> = {
  DAY: 'Tag',
  MONTH: 'Monat',
  YEAR: 'Jahr',
}

export function datePrecisionLabel(precision: DatePrecision | string | null | undefined): string {
  if (!precision) return ''
  return datePrecisionLabels[precision as DatePrecision] ?? precision
}

/**
 * A 0..1 share as a whole German percentage - the one rendering of the Füllgrad and the
 * Pflege-Anker (#1069), so the same figure never appears as "64 %" in one place and "63,6 %" in
 * another.
 */
export function formatShare(share: number): string {
  return `${Math.round(share * 100)} %`
}

const capabilityLabels: Record<Capability, string> = {
  CREATE_SPACE: 'Spaces anlegen',
  CREATE_LIBRARY: 'Bibliotheken für Uploads anlegen',
  CREATE_CONNECTOR_LIBRARY: 'Konnektorbibliotheken anlegen',
  CREATE_INTERNAL_GROUP: 'Interne Gruppen anlegen',
}

/**
 * The sentence a creation dialog shows for a missing Anlegerecht - word for word the one the
 * backend answers with, so the explanation before the attempt and the refusal after it do not
 * differ. `CapabilityService#requireCapability` holds the other copy; its wording is asserted in
 * `CapabilityEnforcementIntegrationTest`.
 */
export function capabilityMissingMessage(capability: Capability): string {
  return (
    `Ihnen fehlt das Anlegerecht „${capabilityLabels[capability]}“. ` +
    'Wenden Sie sich an die Systemverwaltung, wenn Sie es benötigen.'
  )
}

/**
 * Die Größe einer Gruppe, wie sie gezeigt werden darf: unterhalb der Mindestgruppengröße hält der
 * Dienst beide Zahlen zurück und die Oberfläche sagt „kleine Gruppe“ statt einer Zahl. Eine leere
 * Gruppe bleibt benannt — sie hat niemanden, den die Unterdrückung schützen müsste; für eine
 * geschützte Gruppe entfällt jede Angabe (ADR-0036, Entscheidung 9).
 */
export function groupSizeLabel(group: {
  activeMemberCount?: number | null
  smallGroup?: boolean | null
  emptyGroup?: boolean | null
  protectedGroup?: boolean | null
}): string | null {
  if (group.protectedGroup) return null
  if (group.emptyGroup) return 'erreicht derzeit niemanden'
  if (group.smallGroup) return 'kleine Gruppe'
  if (group.activeMemberCount == null) return null
  return `${group.activeMemberCount} Mitglieder`
}

/**
 * Die Herkunft als Zusatz zum Namen („Verzeichnis Haus A · /Haus/Abteilung 5“), nie als Präfix im
 * Namen selbst (ADR-0036, Entscheidung 2).
 */
export function groupOriginLabel(group: {
  origin?: GroupOrigin
  provider?: GroupProviderResponse | null
  sourcePath?: string | null
}): string {
  const parts: string[] = [group.provider ? group.provider.displayName : 'intern']
  if (group.provider?.external) parts.push('extern')
  if (group.sourcePath) parts.push(group.sourcePath)
  return parts.join(' · ')
}

/**
 * Warum eine Gruppe nicht mehr als Empfänger gewählt werden kann. Dass bestehende Rechte bleiben,
 * steht im Satz — sonst wird „nicht wählbar“ als Rechteverlust gelesen.
 */
export function groupNotSelectableReason(group: {
  selectable: boolean
  dissolved: boolean
  providerDisabled: boolean
  unmaintained: boolean
}): string | null {
  if (group.selectable) return null
  if (group.dissolved) return 'aufgelöst — bestehende Rechte bleiben'
  if (group.providerDisabled) return 'Anbieter deaktiviert — bestehende Rechte bleiben'
  if (group.unmaintained) return 'Mitgliedschaft eingefroren — bestehende Rechte bleiben'
  return 'derzeit nicht wählbar'
}

const accessBasisLabels: Record<AccessBasis, string> = {
  DIRECT_GRANT: 'Freigabe an Sie',
  GROUP_GRANT: 'Freigabe an eine Gruppe',
  DIRECT_MEMBERSHIP: 'Eigene Mitgliedschaft',
  GROUP_MEMBERSHIP: 'Mitgliedschaft über eine Gruppe',
  ORGANIZATION_WIDE: 'Organisationsweite Freigabe',
  OWNERSHIP: 'Eigentum',
  SYSTEM_ADMINISTRATION: 'Systemverwaltung',
}

/** Der Weg, über den jemand ein Objekt erreicht (ADR-0036, Entscheidung 9). */
export function accessBasisLabel(basis: AccessBasis | string | undefined): string {
  return accessBasisLabels[basis as AccessBasis] ?? String(basis ?? '')
}

const groupMechanismLabels: Record<GroupMechanism, string> = {
  TOKEN: 'Token',
  DIRECTORY: 'Verzeichnisabgleich',
  NONE: 'ohne Anbieter',
}

/**
 * Wodurch die Mitgliedschaft einer Gruppe gepflegt wird — Teil der Herleitung, weil die
 * Genauigkeit der Rechteauskunft daran hängt (ADR-0036, Entscheidung 3).
 */
export function groupMechanismLabel(mechanism: GroupMechanism | string | undefined): string {
  return groupMechanismLabels[mechanism as GroupMechanism] ?? String(mechanism ?? '')
}

/**
 * Das Zuwachssignal an einer Freigabe oder Mitgliedschaft: „23 bei Erteilung, heute 41“. Beide
 * Zahlen unterliegen der „kleine Gruppe“-Unterdrückung; für eine geschützte Gruppe entfällt das
 * Signal ganz (ADR-0036, Entscheidung 9).
 */
export function groupGrowthLabel(
  signal: {
    memberCountAtGrant?: number | null
    memberCountNow?: number | null
    smallGroup?: boolean | null
    emptyGroup?: boolean | null
    protectedGroup?: boolean | null
  },
  grantWord: string,
): string | null {
  if (signal.protectedGroup) return null
  if (signal.emptyGroup) return 'erreicht derzeit niemanden'
  if (signal.smallGroup) return 'kleine Gruppe'
  if (signal.memberCountNow == null) return null
  if (signal.memberCountAtGrant == null) return `heute ${signal.memberCountNow} Mitglieder`
  return `${signal.memberCountAtGrant} bei ${grantWord}, heute ${signal.memberCountNow}`
}
