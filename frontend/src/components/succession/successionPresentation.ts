import type { SuccessionAddressee, SuccessionEntryResponse, SuccessionKind } from '../../types/api'
import { assetTypeLabel } from '../assets/assetTypeRegistry'
import { ageSinceLabel } from '../groups/groupOriginLabels'

export type SuccessionTab = 'open' | 'grants' | 'groups'

export interface SuccessionTabText {
  kind: SuccessionKind
  /** The tab label - what is listed, in everyday words. */
  title: string
  /** One sentence above the list: what an entry means and what usually helps. */
  intro: string
  /** The empty state: when something will show up here, with an example. */
  emptyExample: string
}

export const SUCCESSION_TABS: Record<SuccessionTab, SuccessionTabText> = {
  open: {
    kind: 'OPEN_SUCCESSION',
    title: 'Inhalte ohne Verantwortliche',
    intro:
      'Bibliotheken, Spaces und Gruppen, deren Eigentümerin oder Verantwortliche nicht mehr aktiv ist. Bestimmen Sie eine Nachfolge, dann sind neue Freigaben wieder möglich.',
    emptyExample:
      'Wird das Konto einer Person gesperrt, der eine Bibliothek oder ein Space gehört, erscheint das hier.',
  },
  grants: {
    kind: 'GRANTS_WITHOUT_RECIPIENT',
    title: 'Gruppen ohne aktive Mitglieder',
    intro:
      'Gruppen, die noch Rechte haben, aber kein aktives Mitglied mehr. Die Rechte erreichen niemanden; meist gehören sie zu einer anderen Gruppe.',
    emptyExample:
      'Verlässt die letzte Person eine Gruppe, die Rechte an Bibliotheken hat, erscheint die Gruppe hier.',
  },
  groups: {
    kind: 'GROUP_WITHOUT_EFFECT',
    title: 'Leere Gruppen',
    intro:
      'Interne Gruppen ohne aktive Mitglieder und ohne Rechte. Sie bewirken nichts mehr und können in der Gruppenverwaltung aufgelöst werden.',
    emptyExample:
      'Hat eine interne Gruppe weder aktive Mitglieder noch Rechte, erscheint sie hier.',
  },
}

function objectNoun(entry: SuccessionEntryResponse): string {
  if (entry.objectType === 'SPACE') return 'der Space'
  if (entry.objectType === 'GROUP') return 'die Gruppe'
  return assetTypeLabel(entry.assetType ?? undefined) === 'Prompt-Bibliothek'
    ? 'die Prompt-Bibliothek'
    : 'die Bibliothek'
}

/** The problem of an entry in one sentence, without naming a person. */
export function problemOf(kind: SuccessionKind, entry: SuccessionEntryResponse): string {
  if (kind === 'GRANTS_WITHOUT_RECIPIENT') {
    const objects = entry.affectedObjects === 1 ? '1 Objekt' : `${entry.affectedObjects} Objekten`
    return `Die Gruppe hat Rechte an ${objects}, aber kein aktives Mitglied mehr.`
  }
  if (kind === 'GROUP_WITHOUT_EFFECT') return 'Die Gruppe hat weder aktive Mitglieder noch Rechte.'
  if (entry.objectType === 'GROUP') return 'Die Gruppe hat keine aktiven Verantwortlichen mehr.'
  const noun = objectNoun(entry)
  if (entry.addressee === 'GROUP_STEWARDS') {
    return `Die Gruppe, der ${noun} gehört, hat keine aktiven Mitglieder mehr.`
  }
  if (entry.objectType === 'SPACE') return 'Das Konto, dem der Space gehört, ist nicht mehr aktiv.'
  // An asset's owner may be a person or a directory group; the entry does not say which.
  return `Wem ${noun} gehört, kann nicht mehr handeln.`
}

const ADDRESSEES: Record<SuccessionAddressee, string> = {
  SYSTEM_ADMINISTRATION: 'die Systemverwaltung',
  SPACE_ADMINS: 'die übrigen Administratorinnen und Administratoren des Space',
  GROUP_STEWARDS: 'die Verantwortlichen der Gruppe',
}

/** Who should act, in plain words; the backend's wording is the fallback for a new addressee. */
export function addresseeOf(entry: SuccessionEntryResponse): string {
  return ADDRESSEES[entry.addressee] ?? entry.addresseeLabel ?? ''
}

export type SuccessionNextStep = 'HANDOVER' | 'GROUP_TRANSFER' | 'GROUP_ADMIN'

/**
 * The one recommended way out of an entry: an object goes to a successor, the rights of a group
 * without members go to another group, and a group without stewards, an object of an internal
 * group and a group that holds nothing are settled in the group management.
 */
export function nextStepOf(
  kind: SuccessionKind,
  entry: SuccessionEntryResponse,
): SuccessionNextStep {
  if (kind === 'GRANTS_WITHOUT_RECIPIENT') return 'GROUP_TRANSFER'
  if (entry.objectType === 'GROUP') return 'GROUP_ADMIN'
  if (entry.addressee === 'GROUP_STEWARDS') return 'GROUP_ADMIN'
  return 'HANDOVER'
}

/** "offen seit 8 Tagen", or "gerade erkannt" before the hourly detection run has seen it. */
export function sinceLabel(entry: SuccessionEntryResponse, now: Date = new Date()): string {
  return entry.firstSeenAt
    ? `offen seit ${ageSinceLabel(entry.firstSeenAt, now)}`
    : 'gerade erkannt'
}
