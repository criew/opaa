import type { GroupEffectsResponse } from '../../../types/api'

/** Singular or plural with the count in front: „1 Space“, „3 Spaces“. */
function counted(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`
}

/**
 * What a group is used for, in the short form of the list column: „2 Bibliotheken · 1 Space“.
 * Every asset a group can reach is a library (knowledge or prompt), so the count names libraries.
 * „nicht verwendet“ when the group confers nothing.
 */
export function groupUsageShort(effects: GroupEffectsResponse): string {
  const parts: string[] = []
  if (effects.grantedAssets > 0) {
    parts.push(counted(effects.grantedAssets, 'Bibliothek', 'Bibliotheken'))
  }
  if (effects.spaces > 0) parts.push(counted(effects.spaces, 'Space', 'Spaces'))
  if (effects.ownedAssets > 0) parts.push('Eigentum')
  if (effects.capabilities > 0)
    parts.push(effects.capabilities === 1 ? 'Anlegerecht' : 'Anlegerechte')
  if (effects.scopedAuthorizations > 0) parts.push('Diagnose-Vollmacht')
  return parts.length === 0 ? GROUP_UNUSED : parts.join(' · ')
}

/** The same usage spelled out line by line, for the tooltip behind the short form. */
export function groupUsageDetails(effects: GroupEffectsResponse): string[] {
  const lines: string[] = []
  if (effects.grantedAssets > 0) {
    lines.push(`Rechte an ${counted(effects.grantedAssets, 'Bibliothek', 'Bibliotheken')}`)
  }
  if (effects.spaces > 0) lines.push(`Mitglied in ${counted(effects.spaces, 'Space', 'Spaces')}`)
  if (effects.ownedAssets > 0) {
    lines.push(`Eigentümerin von ${counted(effects.ownedAssets, 'Bibliothek', 'Bibliotheken')}`)
  }
  if (effects.capabilities > 0) {
    lines.push(
      `${counted(effects.capabilities, 'Anlegerecht', 'Anlegerechte')}, zum Beispiel für Bibliotheken oder Gruppen`,
    )
  }
  if (effects.scopedAuthorizations > 0) {
    lines.push(
      `Geltungsbereich von ${counted(effects.scopedAuthorizations, 'Diagnose-Vollmacht', 'Diagnose-Vollmachten')}`,
    )
  }
  return lines
}

export const GROUP_UNUSED = 'nicht verwendet'

export const GROUP_UNUSED_DETAIL =
  'Die Gruppe vermittelt keine Rechte an Bibliotheken, keine Mitgliedschaft in einem Space, ' +
  'kein Eigentum und keine Anlegerechte.'
