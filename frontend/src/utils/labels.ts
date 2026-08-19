import type {
  AssetRole,
  DocumentSourceType,
  DocumentStatus,
  GroupKind,
  LibraryVisibility,
  PermissionSubjectType,
  SpaceRole,
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
  HTTP_DIRECTORY: 'Verzeichnisliste',
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
  HTTP_DIRECTORY: 'Eine im Web erreichbare Verzeichnisliste wird abgerufen.',
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
