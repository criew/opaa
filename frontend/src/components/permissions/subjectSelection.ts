import type { AssetGrantSubjectType, SelectableGroupResponse, UserSummary } from '../../types/api'
import { confirmAction } from '../../stores/confirmStore'
import { groupNotSelectableReason, groupOriginLabel, groupSizeLabel } from '../../utils/labels'

/**
 * Warum die Suche nach einer geschützten Gruppe wie ein leeres Ergebnis aussieht (ADR-0036,
 * Entscheidung 9) - derselbe Satz an jeder Gruppensuche, ob über {@code SubjectPicker} oder direkt
 * über {@code GroupPicker}.
 */
export const PROTECTED_GROUP_SEARCH_HINT =
  'Eine geschützte Gruppe erscheint nur, wenn Sie ihre vollständige Bezeichnung eingeben.'

/**
 * Wer ein Recht bekommen soll: genau eine Person, genau eine Gruppe — oder alle Konten der
 * Organisation (#1820, #1931). Die dritte Art benennt niemanden; sie erscheint nur dort, wo der
 * Aufrufer sie ausdrücklich zulässt (Freigaben ja, Space-Mitgliedschaften nein).
 */
export interface SubjectSelection {
  type: AssetGrantSubjectType
  user: UserSummary | null
  group: SelectableGroupResponse | null
}

export const emptySubjectSelection: SubjectSelection = { type: 'USER', user: null, group: null }

/**
 * Die gewählte Kennung, oder null, solange nichts gewählt ist — und immer null für „Alle
 * Beschäftigten", die keine Zeile benennen.
 */
export function selectedSubjectId(selection: SubjectSelection): string | null {
  if (selection.type === 'ALL_ACCOUNTS') return null
  return selection.type === 'USER' ? (selection.user?.id ?? null) : (selection.group?.id ?? null)
}

/**
 * Die Rückfrage vor einer Freigabe an alle Konten (#1931, ADR-0037 Entscheidung 9). Sie
 * spricht die Reichweite aus, statt sie nur zu beschriften: Seit die organisationsweite Reichweite
 * eine Freigabe wie jede andere ist, liegt sie einen Klick neben „Team Recht" — dieser Schritt ist
 * die Gegenmaßnahme dagegen.
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

/** Die Zusatzzeile unter dem Namen: Herkunft, Quellpfad, Größe — und der Grund, falls gesperrt. */
export function groupDetailLine(group: SelectableGroupResponse): string {
  const parts = [groupOriginLabel(group)]
  const size = groupSizeLabel(group)
  if (size) parts.push(size)
  const reason = groupNotSelectableReason(group)
  if (reason) parts.push(reason)
  return parts.join(' · ')
}

/** Der Name, unter dem eine Gruppe hier erscheint — eine geschützte bleibt namenlos. */
export function groupLabel(group: SelectableGroupResponse): string {
  return group.name ?? 'Geschützte Gruppe'
}

/**
 * Die Zwischenfrage vor dem Erteilen an eine Gruppe eines externen Anbieters (ADR-0036,
 * Entscheidung 2): Ein Zusatz, den man zum zehnten Mal liest, wird nicht mehr gelesen — ein
 * Fehlklick zwischen zwei gleichnamigen Gruppen gäbe einen internen Vorgang an ein Partnerportal
 * frei. Ohne externe Gruppe fragt nichts zurück.
 */
export async function confirmExternalSubject(selection: SubjectSelection): Promise<boolean> {
  const group = selection.type === 'GROUP' ? selection.group : null
  if (!group) return true
  return confirmGroupSubject(group)
}

/**
 * Dieselbe Entscheidung für eine Gruppe, die nicht aus der Auswahl, sondern über ihre Kennung kam:
 * Dort ist die Herkunft nirgends zu sehen, deshalb nennt die Rückfrage sie — und für einen externen
 * Anbieter ist sie dieselbe Zwischenfrage wie in der Auswahl. Ohne diesen Weg wäre die
 * Zwischenfrage über die Kennung umgehbar.
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
 * Die Rückfrage für den Kennungsweg: Sie zeigt, was die Auswahl sonst zeigt — Name, Herkunft,
 * Quellpfad und Größe der aufgelösten Gruppe —, bevor ein Recht erteilt wird.
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
