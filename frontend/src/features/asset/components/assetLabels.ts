import type {
  AccessBasis,
  AssetGrantSubjectType,
  AssetRole,
  AssetType,
  CatalogEntryStatus,
  PermissionSubjectType,
  SpaceMembershipCounts,
} from '@/shared/api/types'
import { isAssetTypeOffered } from '@/shared/session/installationFeatures'
import { allAccountsLabel } from '@/shared/utils/labels'

/**
 * Groups and persons as one short phrase ("2 Gruppen, 1 Person"); a group counts once, whatever
 * its size. Only valid where the caller is known to be among those counted: exactly one person and
 * no group then means that person is the caller - "nur Sie". Every other case is counted plainly,
 * the caller included.
 */
function groupsAndPersonsLabel(groupCount: number, userCount: number): string {
  if (groupCount === 0 && userCount <= 1) return 'nur Sie'
  const parts: string[] = []
  if (groupCount > 0) parts.push(groupCount === 1 ? '1 Gruppe' : `${groupCount} Gruppen`)
  if (userCount > 0) parts.push(userCount === 1 ? '1 Person' : `${userCount} Personen`)
  return parts.join(', ')
}

/**
 * Who belongs to a space, worded like an asset's reach. The caller is a member of every listed
 * space, so a single person row without a group is the caller - whether or not it is the default
 * space.
 */
export function spaceMembershipLabel(counts: SpaceMembershipCounts | undefined): string {
  if (!counts) return ''
  return groupsAndPersonsLabel(counts.groupCount, counts.userCount)
}

// One sentence per role and asset type, mirroring the graded ranking documented on the AssetRole
// schema in opaa-api.yaml (VIEWER < EDITOR < MANAGER < OWNER, deliberately separate from
// SpaceRole) - each additionally implies everything the role below it already permits, so every
// sentence below starts with "zusätzlich" except VIEWER's, which is the baseline a grant can carry.
const assetRoleDescriptions: Record<AssetType, Record<AssetRole, string>> = {
  KNOWLEDGE_LIBRARY: {
    VIEWER: 'Darf die Bibliothek benutzen und ihren Inhalt einsehen.',
    EDITOR: 'Darf zusätzlich Dokumente ändern, hochladen und entfernen.',
    MANAGER: 'Darf zusätzlich Rechte vergeben und die Sichtbarkeit der Bibliothek ändern.',
    OWNER: 'Darf zusätzlich die Bibliothek löschen und das Eigentum übertragen.',
  },
  PROMPT_LIBRARY: {
    VIEWER: 'Darf die Prompts der Prompt-Bibliothek lesen und einsetzen.',
    EDITOR: 'Darf zusätzlich Prompts anlegen, ändern und löschen.',
    MANAGER: 'Darf zusätzlich Rechte vergeben und die Sichtbarkeit der Prompt-Bibliothek ändern.',
    OWNER: 'Darf zusätzlich die Prompt-Bibliothek samt ihrer Prompts löschen.',
  },
}

export function assetRoleDescription(
  role: AssetRole | string | undefined,
  assetType: AssetType,
): string {
  if (!role || !isAssetTypeOffered(assetType)) return ''
  return assetRoleDescriptions[assetType]?.[role as AssetRole] ?? ''
}

const assetGrantScopeHints: Record<AssetType, string> = {
  KNOWLEDGE_LIBRARY:
    'Eine Freigabe gewährt Zugriff auf alle Dokumente dieser Bibliothek, nicht auf eine Auswahl.',
  PROMPT_LIBRARY:
    'Eine Freigabe gewährt Zugriff auf alle Prompts dieser Prompt-Bibliothek, nicht auf eine Auswahl.',
}

/** What one grant opens on an asset of this type - the first sentence of the rights dialog. */
export function assetGrantScopeHint(assetType: AssetType): string {
  return isAssetTypeOffered(assetType) ? (assetGrantScopeHints[assetType] ?? '') : ''
}

const permissionSubjectTypeLabels: Record<AssetGrantSubjectType, string> = {
  USER: 'Person',
  GROUP: 'Gruppe',
  ALL_ACCOUNTS: allAccountsLabel,
}

export function permissionSubjectTypeLabel(
  subjectType: AssetGrantSubjectType | PermissionSubjectType | string | undefined,
): string {
  if (!subjectType) return ''
  return permissionSubjectTypeLabels[subjectType as AssetGrantSubjectType] ?? subjectType
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

/** The path through which someone reaches an object (ADR-0036, Entscheidung 9). */
export function accessBasisLabel(basis: AccessBasis | string | undefined): string {
  return accessBasisLabels[basis as AccessBasis] ?? String(basis ?? '')
}

/**
 * A group's current size on a grant or space membership, without a minimum
 * group size. For a protected group no figure is given (ADR-0036, Entscheidung 9).
 */
export function groupMemberCountLabel(row: {
  activeMemberCount?: number | null
  protectedGroup?: boolean | null
}): string | null {
  if (row.protectedGroup || row.activeMemberCount == null) return null
  if (row.activeMemberCount === 0) return 'erreicht derzeit niemanden'
  return row.activeMemberCount === 1 ? '1 Mitglied' : `${row.activeMemberCount} Mitglieder`
}

const catalogStatusLabels: Record<Exclude<CatalogEntryStatus, 'READY'>, string> = {
  UPDATING: 'Wird aktualisiert',
  UPDATE_FAILED: 'Aktualisierung fehlgeschlagen',
  NOT_YET_AVAILABLE: 'Noch kein Inhalt',
  SUCCESSION_OPEN: 'Nachfolge offen',
}

/** The word for a state that is not READY; READY needs no word. */
export function catalogStatusLabel(status: CatalogEntryStatus | string | undefined): string {
  if (!status || status === 'READY') return ''
  return catalogStatusLabels[status as Exclude<CatalogEntryStatus, 'READY'>] ?? status
}
