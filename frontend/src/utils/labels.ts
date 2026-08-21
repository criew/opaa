import type {
  AssetRole,
  DocumentSourceType,
  DocumentStatus,
  GroupKind,
  IndexingRunEventCategory,
  LibraryVisibility,
  PermissionSubjectType,
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
// CreateSpaceDialog and SpaceManagementPage render their Select options in - a single source
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
  UPLOAD: 'Hochgeladen',
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

// One sentence per source type, shown as the template description in CreateLibraryDialog.
const documentSourceTypeDescriptions: Record<DocumentSourceType, string> = {
  UPLOAD: 'Dokumente werden manuell hochgeladen und einzeln verwaltet.',
  FILESYSTEM: 'Ein Verzeichnis auf dem Server wird regelmäßig eingelesen.',
  HTTP_DIRECTORY:
    'Eine über das Web (http/https) erreichbare Verzeichnisseite wird abgerufen, kein lokaler Ordner.',
  RSS_FEED: 'Ein RSS-Feed und die verlinkten Detailseiten werden abgerufen.',
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
 * Which configuration fields CreateLibraryDialog renders and validates for each source type,
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
  // #404: indexed anyway, only the deviation between the file's own extension and its detected
  // content is reported here.
  FORMAT_MISMATCH: 'Endung weicht vom Inhalt ab',
}

export function indexingRunEventCategoryLabel(
  category: IndexingRunEventCategory | string | undefined,
): string {
  if (!category) return ''
  return indexingRunEventCategoryLabels[category as IndexingRunEventCategory] ?? category
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
