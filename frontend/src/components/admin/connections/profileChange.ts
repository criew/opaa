import type {
  ConnectionOwnership,
  ConnectionProfileResponse,
  ConnectionProfileUpdateRequest,
  PersonCount,
  SourceChangeRejectionCategory,
} from '../../../types/api'

function blank(value: string | null | undefined): string {
  return value ?? ''
}

/**
 * Whether an edit may reach the libraries on the profile or the connected accounts of persons -
 * anything but a rename or a new client secret - and is therefore previewed first. What it
 * discards names the server's preview alone.
 */
export function reachesLibraries(
  profile: ConnectionProfileResponse,
  update: ConnectionProfileUpdateRequest,
): boolean {
  return (
    blank(profile.serverUrl) !== blank(update.serverUrl).replace(/\/+$/, '') ||
    profile.authMethod !== update.authMethod ||
    profile.ownership !== update.ownership ||
    blank(profile.clientId) !== blank(update.clientId) ||
    blank(profile.tenant) !== blank(update.tenant) ||
    blank(profile.scopes) !== blank(update.scopes) ||
    blank(profile.authorizationEndpoint) !== blank(update.authorizationEndpoint) ||
    blank(profile.tokenEndpoint) !== blank(update.tokenEndpoint) ||
    blank(profile.revocationEndpoint) !== blank(update.revocationEndpoint) ||
    blank(profile.sourceProxy) !== blank(update.sourceProxy) ||
    profile.sourceInsecureSsl !== Boolean(update.sourceInsecureSsl) ||
    JSON.stringify(profile.connectorSettings ?? null) !==
      JSON.stringify(update.connectorSettings ?? null)
  )
}

/** Whether connections of persons may exist on a profile of this ownership. */
export function admitsPersons(ownership: ConnectionOwnership): boolean {
  switch (ownership) {
    case 'PERSON':
    case 'BOTH':
      return true
    case 'LIBRARY':
      return false
    default: {
      const unknown: never = ownership
      throw new Error(`Unknown connection ownership ${String(unknown)}`)
    }
  }
}

/**
 * A number of persons exactly as the API rounds it: the count, or "weniger als N". Nothing is
 * derived from it; a count the API withholds reads "nicht ausgewiesen".
 */
export function personCountLabel(persons: PersonCount | null | undefined): string {
  if (persons?.fewerThan != null) return `weniger als ${persons.fewerThan}`
  if (persons?.count != null) return String(persons.count)
  return 'nicht ausgewiesen'
}

/** The German name of the kind of a connector's refusal. */
export function rejectionCategoryLabel(category: SourceChangeRejectionCategory): string {
  switch (category) {
    case 'CONNECTION':
      return 'Verbindung'
    case 'SETTINGS':
      return 'Einstellungen'
    default: {
      const unknown: never = category
      throw new Error(`Unknown rejection category ${String(unknown)}`)
    }
  }
}
