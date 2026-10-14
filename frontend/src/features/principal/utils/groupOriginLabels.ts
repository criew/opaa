import type { GroupListResponse, GroupOrigin } from '@/shared/api/types'

/** The origin as a word — „intern" or the provider's name (ADR-0036, Entscheidung 2). */
export function groupOriginLabel(group: {
  origin: GroupOrigin
  provider?: GroupListResponse['provider']
}): string {
  if (group.origin === 'INTERNAL') return 'intern'
  return group.provider?.displayName ?? 'Anbieter'
}

/**
 * Why this group can no longer be newly chosen anywhere — `null` as long as it is effective. The
 * four reasons are the ones the backend rejects as the target of a permission or a transfer
 * (`GroupSubject`: dissolved, provider deactivated, frozen token group, deactivated
 * internal group). Existing permissions stay stored; only for a deactivated
 * group they rest until it is reactivated (ADR-0036, Entscheidungen 2, 3 and 14).
 */
export function groupIneffectiveReason(group: GroupListResponse): string | null {
  if (group.dissolved) {
    return 'Aufgelöst — die Quelle meldet diese Gruppe nicht mehr. Ihre Mitgliedschaft ist eingefroren; bestehende Berechtigungen bleiben.'
  }
  if (group.provider && !group.provider.enabled) {
    return `Anbieter „${group.provider.displayName}" ist deaktiviert — die Gruppe ist kein neues Ziel für Berechtigungen und keine neue Space-Mitgliedschaft. Bestehende Berechtigungen bleiben.`
  }
  if (group.deactivated) {
    return 'Deaktiviert — die Gruppe wirkt vorübergehend für niemanden: Ihre Freigaben, Space-Mitgliedschaften und Anlegerechte ruhen und sie ist kein neues Ziel. Alles bleibt gespeichert; „Reaktivieren“ stellt den vorherigen Stand wieder her.'
  }
  if (group.kind === 'IDENTITY_PROVIDER' && group.provider?.groupMechanism === 'DIRECTORY') {
    return 'Wird nicht mehr gepflegt — ihr Anbieter liefert seine Gruppen inzwischen über den Verzeichnisabgleich. Ihre Mitgliedschaft ist eingefroren; wählen Sie die entsprechende Organisationseinheit.'
  }
  return null
}

/** The age of a point in time in days or hours — the figure of the operations and plan rows. */
export function ageLabel(iso: string, now: Date = new Date()): string {
  const millis = now.getTime() - new Date(iso).getTime()
  if (Number.isNaN(millis)) return 'unbekannt'
  const hours = Math.floor(millis / 3_600_000)
  if (hours < 1) return 'unter einer Stunde'
  if (hours < 48) return hours === 1 ? '1 Stunde' : `${hours} Stunden`
  const days = Math.floor(hours / 24)
  return `${days} Tage`
}

/** The same age after „seit", which needs the dative: „seit 8 Tagen", „seit einer Stunde". */
export function ageSinceLabel(iso: string, now: Date = new Date()): string {
  const millis = now.getTime() - new Date(iso).getTime()
  if (Number.isNaN(millis)) return 'unbekannter Zeit'
  const hours = Math.floor(millis / 3_600_000)
  if (hours < 1) return 'weniger als einer Stunde'
  if (hours < 48) return hours === 1 ? 'einer Stunde' : `${hours} Stunden`
  return `${Math.floor(hours / 24)} Tagen`
}

/** „1 Konto" / „3 Konten" and the like - a count with its noun in the right number. */
export function countLabel(count: number, one: string, many: string): string {
  return `${count} ${count === 1 ? one : many}`
}
