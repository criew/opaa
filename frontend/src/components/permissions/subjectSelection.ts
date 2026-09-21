import type { PermissionSubjectType, SelectableGroupResponse, UserSummary } from '../../types/api'
import { confirmAction } from '../../stores/confirmStore'
import { groupNotSelectableReason, groupOriginLabel, groupSizeLabel } from '../../utils/labels'

/** Wer ein Recht bekommen soll: genau eine Person oder genau eine Gruppe (#1820). */
export interface SubjectSelection {
  type: PermissionSubjectType
  user: UserSummary | null
  group: SelectableGroupResponse | null
}

export const emptySubjectSelection: SubjectSelection = { type: 'USER', user: null, group: null }

/** Die gewählte Kennung, oder null, solange nichts gewählt ist. */
export function selectedSubjectId(selection: SubjectSelection): string | null {
  return selection.type === 'USER' ? (selection.user?.id ?? null) : (selection.group?.id ?? null)
}

/** Die Zusatzzeile unter dem Namen: Herkunft, Quellpfad, Größe — und der Grund, falls gesperrt. */
export function groupDetailLine(group: SelectableGroupResponse): string {
  const parts = [groupOriginLabel(group)]
  const size = groupSizeLabel(group)
  if (size) parts.push(size)
  const reason = groupNotSelectableReason(group)
  if (reason) parts.push(reason)
  return parts.join(' · ')
}

/**
 * Die Zwischenfrage vor dem Erteilen an eine Gruppe eines externen Anbieters (ADR-0036,
 * Entscheidung 2): Ein Zusatz, den man zum zehnten Mal liest, wird nicht mehr gelesen — ein
 * Fehlklick zwischen zwei gleichnamigen Gruppen gäbe einen internen Vorgang an ein Partnerportal
 * frei. Ohne externe Gruppe fragt nichts zurück.
 */
export async function confirmExternalSubject(selection: SubjectSelection): Promise<boolean> {
  const group = selection.type === 'GROUP' ? selection.group : null
  if (!group?.provider?.external) return true
  return confirmAction({
    question: 'Sie geben für eine Gruppe eines externen Anbieters frei — fortfahren?',
    consequence:
      `„${group.name}“ stammt aus dem Anbieter „${group.provider.displayName}“, der einer ` +
      'anderen Organisation gehört. Wer dort Mitglied ist, entscheidet nicht diese Stelle.',
    confirmLabel: 'Fortfahren',
    tone: 'caution',
  })
}
