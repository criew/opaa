import type { GroupKind, GroupListResponse, GroupState } from '../../../types/api'
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

export const GROUP_KINDS: GroupKind[] = ['AD_HOC', 'IDENTITY_PROVIDER', 'ORG_UNIT']

/** The kind as the list reads it - short, where the dialog may say more. */
export const GROUP_KIND_SHORT_LABEL: Record<GroupKind, string> = {
  AD_HOC: 'Ad-hoc-Gruppe',
  IDENTITY_PROVIDER: 'Aus dem Anbieter',
  ORG_UNIT: 'Organisationseinheit',
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
