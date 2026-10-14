import type {
  AssetGrantSubjectType,
  GroupOrigin,
  GroupProviderResponse,
  SelectableGroupResponse,
  UserSummary,
} from '@/shared/api/types'
import { confirmAction } from '@/shared/stores/confirmStore'

/**
 * Why the search for a protected group looks like an empty result (ADR-0036,
 * Entscheidung 9) - the same sentence on every group search, whether via {@code SubjectPicker} or
 * directly via {@code GroupPicker}.
 */
export const PROTECTED_GROUP_SEARCH_HINT =
  'Eine geschützte Gruppe erscheint nur, wenn Sie ihre vollständige Bezeichnung eingeben.'

/**
 * Who is to receive a right: exactly one person, exactly one group — or all accounts of the
 * organization. The third kind names nobody; it appears only where the
 * caller explicitly admits it (grants yes, space memberships no).
 */
export interface SubjectSelection {
  type: AssetGrantSubjectType
  user: UserSummary | null
  group: SelectableGroupResponse | null
}

export const emptySubjectSelection: SubjectSelection = { type: 'USER', user: null, group: null }

/**
 * The chosen identifier, or null as long as nothing is chosen — and always null for „Alle
 * Beschäftigten", which names no row.
 */
export function selectedSubjectId(selection: SubjectSelection): string | null {
  if (selection.type === 'ALL_ACCOUNTS') return null
  return selection.type === 'USER' ? (selection.user?.id ?? null) : (selection.group?.id ?? null)
}

/**
 * The confirmation before a release to all accounts (ADR-0037 Entscheidung 9). It
 * spells out the reach instead of only labelling it: since the organization-wide reach
 * is a grant like any other, it lies one click next to „Team Recht" — this step is
 * the countermeasure.
 */
export async function confirmAllAccountsSubject(roleLabel: string): Promise<boolean> {
  return confirmAction({
    question: 'An alle Konten freigeben?',
    consequence:
      `Jede Person Ihrer Organisation erhält damit die Rolle „${roleLabel}" — ohne eine weitere ` +
      'Freigabe und ohne Zutun der Empfänger. Sie können die Freigabe jederzeit zurücknehmen.',
    confirmLabel: 'An alle freigeben',
    tone: 'caution',
  })
}

/** The additional line below the name: origin, source path, size — and the reason, if disabled. */
export function groupDetailLine(group: SelectableGroupResponse): string {
  const parts = [groupOriginLabel(group)]
  const size = groupSizeLabel(group)
  if (size) parts.push(size)
  const reason = groupNotSelectableReason(group)
  if (reason) parts.push(reason)
  return parts.join(' · ')
}

/** The name under which a group appears here — a protected one stays nameless. */
export function groupLabel(group: SelectableGroupResponse): string {
  return group.name ?? 'Geschützte Gruppe'
}

/**
 * The confirmation question before granting to a group of an external provider (ADR-0036,
 * Entscheidung 2): an addition read for the tenth time is no longer read — a
 * misclick between two groups of the same name would release an internal matter to a partner
 * portal. Without an external group nothing asks back.
 */
export async function confirmExternalSubject(selection: SubjectSelection): Promise<boolean> {
  const group = selection.type === 'GROUP' ? selection.group : null
  if (!group) return true
  return confirmGroupSubject(group)
}

/**
 * The same decision for a group that came not from the selection but via its identifier:
 * there the origin is nowhere to be seen, so the confirmation names it — and for an external
 * provider it is the same confirmation question as in the selection. Without this path the
 * question could be bypassed via the identifier.
 */
export async function confirmGroupSubject(group: SelectableGroupResponse): Promise<boolean> {
  if (group.provider?.external) {
    return confirmAction({
      question: 'Sie geben für eine Gruppe eines externen Anbieters frei — fortfahren?',
      consequence:
        `„${groupLabel(group)}“ stammt aus dem Anbieter „${group.provider.displayName}“, der ` +
        'einer anderen Organisation gehört. Wer dort Mitglied ist, entscheidet nicht diese Stelle.',
      confirmLabel: 'Fortfahren',
      tone: 'caution',
    })
  }
  return true
}

/**
 * The confirmation for the identifier path: it shows what the selection otherwise shows — name,
 * origin, source path and size of the resolved group —, before a right is granted.
 */
export async function confirmResolvedGroupById(group: SelectableGroupResponse): Promise<boolean> {
  const confirmed = await confirmAction({
    question: `Recht an „${groupLabel(group)}“ erteilen?`,
    consequence: groupDetailLine(group),
    confirmLabel: 'Weiter',
    tone: 'caution',
  })
  if (!confirmed) return false
  return confirmGroupSubject(group)
}

/**
 * A group's size as it may be shown: below the minimum group size the service
 * withholds both numbers and the interface says „kleine Gruppe“ instead of a number. An empty
 * group stays named — it has nobody the suppression would have to protect; for a
 * protected group no figure is given (ADR-0036, Entscheidung 9).
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
 * The origin as an addition to the name („Verzeichnis Haus A · /Haus/Abteilung 5“), never as a
 * prefix in the name itself (ADR-0036, Entscheidung 2).
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
 * Why a group can no longer be chosen as a recipient. That existing rights remain is
 * stated in the sentence — otherwise „nicht wählbar“ is read as a loss of rights.
 */
export function groupNotSelectableReason(group: {
  selectable: boolean
  dissolved: boolean
  providerDisabled: boolean
  unmaintained: boolean
  deactivated: boolean
}): string | null {
  if (group.selectable) return null
  if (group.dissolved) return 'aufgelöst — bestehende Rechte bleiben'
  if (group.providerDisabled) return 'Anbieter deaktiviert — bestehende Rechte bleiben'
  if (group.unmaintained) return 'Mitgliedschaft eingefroren — bestehende Rechte bleiben'
  if (group.deactivated) return 'Gruppe deaktiviert — bestehende Rechte ruhen'
  return 'derzeit nicht wählbar'
}
