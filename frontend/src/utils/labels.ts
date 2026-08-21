import type {
  AssetRole,
  DocumentSourceType,
  DocumentStatus,
  GroupKind,
  IndexingRunEventCategory,
  LibraryVisibility,
  PermissionSubjectType,
  ScheduleFrequency,
  ScheduleWeekday,
  SpaceRole,
  SpaceVisibility,
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
// SpaceCreatePage and SpaceManagementPage render their Select options in - a single source
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

const libraryVisibilityLabels: Record<LibraryVisibility, string> = {
  PRIVATE: 'privat',
  SHARED: 'geteilt',
  ORGANIZATION: 'organisationsweit',
}

export function libraryVisibilityLabel(visibility: LibraryVisibility | string | undefined): string {
  if (!visibility) return ''
  return libraryVisibilityLabels[visibility as LibraryVisibility] ?? visibility
}

/** Render order of the distribution levels in LibraryCreatePage and LibraryDetailPage. */
export const libraryVisibilities = Object.keys(libraryVisibilityLabels) as LibraryVisibility[]

// One sentence per distribution level, following the semantics documented on the
// LibraryVisibility schema in opaa-api.yaml and docs/features/spaces-and-assets.md.
const libraryVisibilityDescriptions: Record<LibraryVisibility, string> = {
  PRIVATE: 'Nur der Eigentümer nutzt den Bestand — bei Gruppen-Eigentum die Mitglieder der Gruppe.',
  SHARED: 'Die Reichweite bestimmen die Freigaben an Personen und Gruppen.',
  ORGANIZATION: 'Lesbar für alle Nutzer der Organisation.',
}

export function libraryVisibilityDescription(
  visibility: LibraryVisibility | string | undefined,
): string {
  if (!visibility) return ''
  return libraryVisibilityDescriptions[visibility as LibraryVisibility] ?? ''
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
 *
 * Just like documentSourceTypeLabels, this is a Record over the full DocumentSourceType union, so
 * a future enum value forces a compile error here instead of silently rendering as a template with
 * no configuration fields at all.
 */
export const documentSourceTypeConfigKind: Record<DocumentSourceType, 'none' | 'path' | 'url'> = {
  UPLOAD: 'none',
  FILESYSTEM: 'path',
  HTTP_DIRECTORY: 'url',
  RSS_FEED: 'url',
}

// #513: German, understandable categories for a skipped/rejected item or error in a run's
// protocol - matches io.opaa.indexing.IndexingEventCategory's own Javadoc one-to-one.
const indexingRunEventCategoryLabels: Record<IndexingRunEventCategory, string> = {
  REJECTED: 'Abgewiesen',
  UNREACHABLE: 'Nicht erreichbar',
  UNSUPPORTED_FORMAT: 'Format nicht unterstützt',
  ALLOWLIST: 'Allowlist',
  ERROR: 'Fehler',
  SCHEDULE_SKIPPED: 'Geplanter Lauf übersprungen',
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
