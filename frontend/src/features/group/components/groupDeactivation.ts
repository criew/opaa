import type { GroupDeactivationPreviewResponse } from '@/shared/api/types'
import { getGroupDeactivationPreview } from '../api/groupApi'
import { confirmAction } from '@/shared/stores/confirmStore'
import { notify } from '@/shared/stores/notificationStore'
import { countLabel } from '@/features/principal'

/** What depends on the group, as an enumeration — empty when it carries nothing. */
function effectParts(preview: GroupDeactivationPreviewResponse): string[] {
  const parts: string[] = []
  if (preview.assetGrants > 0) {
    parts.push(
      `${countLabel(preview.assetGrants, 'Freigabe', 'Freigaben')} an ${countLabel(preview.grantedAssets, 'Objekt', 'Objekten')}`,
    )
  }
  if (preview.spaceMemberships > 0) {
    parts.push(
      `${countLabel(preview.spaceMemberships, 'Space-Mitgliedschaft', 'Space-Mitgliedschaften')} in ${countLabel(preview.spaces, 'Space', 'Spaces')}`,
    )
  }
  if (preview.capabilities > 0) {
    parts.push(countLabel(preview.capabilities, 'Anlegerecht', 'Anlegerechte'))
  }
  return parts
}

function joined(parts: string[]): string {
  if (parts.length <= 1) return parts.join('')
  return `${parts.slice(0, -1).join(', ')} und ${parts[parts.length - 1]}`
}

/**
 * The quantified consequences for the confirmation (ADR-0036, Entscheidung 14): what rests or
 * applies again and how many accounts it affects.
 */
export function deactivationConsequence(
  preview: GroupDeactivationPreviewResponse,
  deactivate: boolean,
): string {
  const parts = effectParts(preview)
  const accounts = countLabel(preview.activeMembers, 'aktives Konto', 'aktive Konten')
  if (deactivate) {
    const effects =
      parts.length === 0
        ? 'Die Gruppe trägt zurzeit keine Freigaben, Space-Mitgliedschaften oder Anlegerechte.'
        : `${joined(parts)} ruhen für ${accounts}.`
    return (
      `Solange die Gruppe deaktiviert ist, wirkt sie für niemanden: ${effects} ` +
      'Sie ist kein neues Ziel für Berechtigungen. Gespeichert bleibt alles; „Reaktivieren“ stellt ' +
      'den vorherigen Stand ohne weitere Entscheidung wieder her. Die Änderung gilt sofort, auch in ' +
      'laufenden Sitzungen.'
    )
  }
  const effects =
    parts.length === 0
      ? 'Die Gruppe trägt keine Freigaben, Space-Mitgliedschaften oder Anlegerechte.'
      : `${joined(parts)} gelten wieder für ${accounts}.`
  return `Die Gruppe wirkt sofort wieder: ${effects}`
}

/**
 * Asks with the quantified consequences before deactivating or reactivating and performs the
 * action. If something stands in the way of deactivating (ownership, last capable
 * space administration), a message names the reason instead of asking.
 *
 * @returns whether the state has changed
 */
export async function confirmGroupDeactivation(
  group: { id: string; name: string; deactivated: boolean },
  apply: (deactivated: boolean) => Promise<unknown>,
): Promise<boolean> {
  const deactivate = !group.deactivated
  let preview: GroupDeactivationPreviewResponse
  try {
    preview = await getGroupDeactivationPreview(group.id)
  } catch (err) {
    notify(
      err instanceof Error ? err.message : 'Die Folgen konnten nicht ermittelt werden.',
      'error',
    )
    return false
  }
  if (deactivate && preview.blockedReason) {
    notify(preview.blockedReason, 'error')
    return false
  }
  const confirmed = await confirmAction({
    question: deactivate ? `„${group.name}“ deaktivieren?` : `„${group.name}“ reaktivieren?`,
    consequence: deactivationConsequence(preview, deactivate),
    confirmLabel: deactivate ? 'Deaktivieren' : 'Reaktivieren',
    tone: deactivate ? 'caution' : 'neutral',
  })
  if (!confirmed) return false
  try {
    await apply(deactivate)
  } catch (err) {
    notify(
      err instanceof Error
        ? err.message
        : deactivate
          ? 'Die Gruppe konnte nicht deaktiviert werden.'
          : 'Die Gruppe konnte nicht reaktiviert werden.',
      'error',
    )
    return false
  }
  notify(
    deactivate ? `„${group.name}“ ist deaktiviert.` : `„${group.name}“ wirkt wieder.`,
    'success',
  )
  return true
}
