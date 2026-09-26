import type { GroupListResponse, GroupState } from '../../../types/api'
import { groupIneffectiveReason } from '../../groups/groupOriginLabels'

/** The states in the order the filter offers them - what needs a decision after „Aktiv". */
export const GROUP_STATES: GroupState[] = [
  'ACTIVE',
  'NOT_RELEASED',
  'UNMAINTAINED',
  'PROVIDER_DISABLED',
  'DISSOLVED',
]

export const GROUP_STATE_LABEL: Record<GroupState, string> = {
  ACTIVE: 'Aktiv',
  NOT_RELEASED: 'Nicht freigegeben',
  UNMAINTAINED: 'Nicht mehr gepflegt',
  PROVIDER_DISABLED: 'Anbieter deaktiviert',
  DISSOLVED: 'Aufgelöst',
}

/** Meaning-only colour of the state dot, in step with the account list's states. */
export const GROUP_STATE_COLOR: Record<GroupState, string> = {
  ACTIVE: 'success.main',
  NOT_RELEASED: 'text.disabled',
  UNMAINTAINED: 'warning.main',
  PROVIDER_DISABLED: 'warning.main',
  DISSOLVED: 'error.main',
}

/** A sync interval in words: „alle 15 Minuten", „stündlich", „alle 6 Stunden", „täglich". */
function intervalText(minutes: number): string {
  if (minutes % 1440 === 0) return minutes === 1440 ? 'täglich' : `alle ${minutes / 1440} Tage`
  if (minutes % 60 === 0) return minutes === 60 ? 'stündlich' : `alle ${minutes / 60} Stunden`
  return `alle ${minutes} Minuten`
}

/**
 * How OPAA learns a provider group's members - by reading the directory itself or only from the
 * sign-in - and what follows for their currency, in plain words. Null for an internal group.
 */
export function groupOriginExplanation(group: GroupListResponse): string | null {
  const provider = group.provider
  if (!provider) return null
  const name = `„${provider.displayName}“`
  if (group.kind === 'ORG_UNIT') {
    const rhythm = provider.directorySyncIntervalMinutes
      ? `, zurzeit ${intervalText(provider.directorySyncIntervalMinutes)}`
      : ''
    return (
      `OPAA liest Gruppen und Mitglieder von ${name} selbst aus${rhythm}. Änderungen dort gelten ` +
      'hier nach dem nächsten Abgleich, auch für Personen, die sich nicht anmelden.'
    )
  }
  return (
    'OPAA erfährt die Mitglieder dieser Gruppe nur bei der Anmeldung: Meldet sich jemand über ' +
    `${name} an, teilt der Anbieter mit, in welchen Gruppen die Person ist. Ändert sich dort etwas, ` +
    'sieht OPAA das erst bei der nächsten Anmeldung dieser Person.'
  )
}

/** Why a group is not in effect, for the info symbol behind its state; null for an active one. */
export function groupStateReason(group: GroupListResponse): string | null {
  if (group.state === 'ACTIVE') return null
  if (group.state === 'NOT_RELEASED') {
    return 'Noch nicht zur Verwendung freigegeben — andere Rechtevergebende können die Gruppe erst wählen, wenn sie freigegeben ist.'
  }
  return groupIneffectiveReason(group)
}

export const GROUP_DELETE_CONSEQUENCE =
  'Die Gruppe und ihre Mitgliedschaften werden entfernt; das lässt sich nicht rückgängig machen. ' +
  'Gelöscht wird nur eine Gruppe, die nichts mehr trägt – Freigaben und Eigentum gehen vorher ' +
  'über „Wirkungen übertragen" an eine andere Gruppe.'

export const PROVIDER_GROUP_DELETE_REASON =
  'Gruppen aus einem Identitätsanbieter oder Verzeichnis werden dort gepflegt und verschwinden, ' +
  'wenn die Quelle sie nicht mehr meldet.'
