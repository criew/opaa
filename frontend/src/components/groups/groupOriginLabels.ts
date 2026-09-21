import type { GroupListResponse, GroupMechanism, GroupOrigin } from '../../types/api'

/** Die Herkunft als Wort — „intern" oder der Name des Anbieters (ADR-0036, Entscheidung 2). */
export function groupOriginLabel(group: {
  origin: GroupOrigin
  provider?: GroupListResponse['provider']
}): string {
  if (group.origin === 'INTERNAL') return 'intern'
  return group.provider?.displayName ?? 'Anbieter'
}

const mechanismLabels: Record<GroupMechanism, string> = {
  TOKEN: 'Token bei jeder Anmeldung',
  DIRECTORY: 'Verzeichnisabgleich',
  NONE: 'kein Gruppenmechanismus',
}

export function groupMechanismLabel(mechanism: GroupMechanism | undefined): string {
  return mechanism ? mechanismLabels[mechanism] : mechanismLabels.NONE
}

/**
 * Warum diese Gruppe nirgends mehr neu gewählt werden kann — `null`, solange sie wirksam ist. Die
 * drei Gründe sind dieselben, die das Backend als Ziel einer Berechtigung oder einer Übertragung
 * abweist (`GroupSubject`: aufgelöst, Anbieter deaktiviert, eingefrorene Token-Gruppe); bestehende
 * Berechtigungen bleiben in jedem Fall unangetastet (ADR-0036, Entscheidungen 2 und 3).
 */
export function groupIneffectiveReason(group: GroupListResponse): string | null {
  if (group.dissolved) {
    return 'Aufgelöst — die Quelle meldet diese Gruppe nicht mehr. Ihre Mitgliedschaft ist eingefroren; bestehende Berechtigungen bleiben.'
  }
  if (group.provider && !group.provider.enabled) {
    return `Anbieter „${group.provider.displayName}" ist deaktiviert — die Gruppe ist kein neues Ziel für Berechtigungen und keine neue Space-Mitgliedschaft. Bestehende Berechtigungen bleiben.`
  }
  if (group.kind === 'IDENTITY_PROVIDER' && group.provider?.groupMechanism === 'DIRECTORY') {
    return 'Wird nicht mehr gepflegt — ihr Anbieter liefert seine Gruppen inzwischen über den Verzeichnisabgleich. Ihre Mitgliedschaft ist eingefroren; wählen Sie die entsprechende Organisationseinheit.'
  }
  return null
}

/** Der Eintragstext einer Gruppe in einer Auswahl: Name, Herkunft und — falls nötig — der Grund. */
export function groupOptionLabel(group: GroupListResponse): string {
  const reason = groupIneffectiveReason(group)
  const base = `${group.name} · ${groupOriginLabel(group)}`
  return reason ? `${base} — nicht wählbar: ${reason}` : base
}

/** Das Alter eines Zeitpunkts in Tagen bzw. Stunden — die Angabe der Betriebs- und Planzeilen. */
export function ageLabel(iso: string, now: Date = new Date()): string {
  const millis = now.getTime() - new Date(iso).getTime()
  if (Number.isNaN(millis)) return 'unbekannt'
  const hours = Math.floor(millis / 3_600_000)
  if (hours < 1) return 'unter einer Stunde'
  if (hours < 48) return hours === 1 ? '1 Stunde' : `${hours} Stunden`
  const days = Math.floor(hours / 24)
  return `${days} Tage`
}
