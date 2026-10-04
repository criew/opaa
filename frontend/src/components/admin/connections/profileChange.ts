import type {
  ConnectionProfileResponse,
  ConnectionProfileUpdateRequest,
  SourceChangeRejectionCategory,
} from '../../../types/api'

/** What an edit changes for the libraries on a profile, read from the stored and the new values. */
export interface ProfileChange {
  /** Server address, registration or sign-in: every secret on the profile is discarded. */
  discardsSecrets: boolean
  /** Address, proxy, TLS switch or defaults: the effective configuration of the libraries. */
  changesConfiguration: boolean
  /** Whether the connectors of the libraries are asked at all - not for a mere rename. */
  reachesLibraries: boolean
}

function blank(value: string | null | undefined): string {
  return value ?? ''
}

export function changeOf(
  profile: ConnectionProfileResponse,
  update: ConnectionProfileUpdateRequest,
): ProfileChange {
  const newAddress = profile.serverUrl !== update.serverUrl.replace(/\/+$/, '')
  const discardsSecrets =
    newAddress ||
    profile.authMethod !== update.authMethod ||
    blank(profile.clientId) !== blank(update.clientId) ||
    blank(profile.tenant) !== blank(update.tenant) ||
    blank(profile.scopes) !== blank(update.scopes)
  const changesConfiguration =
    newAddress ||
    blank(profile.sourceProxy) !== blank(update.sourceProxy) ||
    profile.sourceInsecureSsl !== Boolean(update.sourceInsecureSsl) ||
    JSON.stringify(profile.connectorSettings ?? null) !==
      JSON.stringify(update.connectorSettings ?? null)
  return {
    discardsSecrets,
    changesConfiguration,
    reachesLibraries: discardsSecrets || changesConfiguration,
  }
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
