import type {
  ConnectionAuthMethod,
  ConnectionProfileOption,
  SourceTypeDescriptor,
} from '../../../types/api'
import type { SourceFormContext } from './types'

/** The connection profile ("Zugang") a source form works under. */
export interface SourceConnection {
  profileId: string
  name: string
  serverUrl: string
  authMethod: ConnectionAuthMethod
  /** The connector settings the profile fixes for every library on it, by settings key. */
  defaults: Record<string, unknown>
}

export function sourceConnectionOf(option: ConnectionProfileOption): SourceConnection {
  return {
    profileId: option.id,
    name: option.name,
    serverUrl: option.serverUrl,
    authMethod: option.authMethod,
    defaults: option.connectorDefaults ?? {},
  }
}

/** Whether a library signing in this way enters a secret of its own in the source form. */
export function asksLibrarySecret(method: ConnectionAuthMethod): boolean {
  switch (method) {
    case 'PERSONAL_SECRET':
    case 'SERVICE_ACCOUNT_KEY':
      return true
    case 'NONE':
    case 'OAUTH':
    case 'CLIENT_CREDENTIALS':
      return false
    default: {
      const unknown: never = method
      throw new Error(`Unknown connection auth method ${String(unknown)}`)
    }
  }
}

/** The request fields that scope a connection test or a listing. */
export interface ProbeScope {
  libraryId?: string
  connectionProfileId?: string
}

/**
 * What every source form derives from its context, by the same rules: with a profile the address
 * starts at the profile's server address, the secret field exists only for a sign-in that takes
 * one, and a setting the profile fixes is shown read-only with the profile's value.
 */
export interface ConnectionFields {
  connection: SourceConnection | null
  probe: ProbeScope
  asksSecret: boolean
  isFixed: (key: string) => boolean
  /** The hint under a fixed field; null without a profile. */
  fixedHint: string | null
  /** The hint under the address field; null without a profile. */
  addressHint: string | null
}

export function connectionFields(context: SourceFormContext): ConnectionFields {
  const connection = context.connection ?? null
  const probe: ProbeScope =
    context.mode === 'edit'
      ? { libraryId: context.libraryId }
      : connection
        ? { connectionProfileId: connection.profileId }
        : {}
  return {
    connection,
    probe,
    asksSecret: connection === null || asksLibrarySecret(connection.authMethod),
    isFixed: (key) => connection !== null && key in connection.defaults,
    fixedHint: connection && `Vom Zugang „${connection.name}“ vorgegeben.`,
    addressHint:
      connection &&
      `Liegt unter der Server-Adresse des Zugangs „${connection.name}“: ${connection.serverUrl}`,
  }
}

/**
 * `values` as a form under `connection` shows them: an empty address takes the profile's server
 * address, and every field named like a fixed setting takes the profile's value. Without a
 * profile the values are returned as they are.
 */
export function withConnection<V>(values: V, connection: SourceConnection | null | undefined): V {
  if (!connection || values === null || typeof values !== 'object') return values
  const shown: Record<string, unknown> = { ...(values as Record<string, unknown>) }
  if (shown.sourceUrl === '') shown.sourceUrl = connection.serverUrl
  for (const [key, value] of Object.entries(connection.defaults)) {
    if (key in shown) shown[key] = value
  }
  return shown as V
}

/** `patch` without the fields a profile fixes - those keep the profile's value. */
export function withoutFixed<P extends object>(
  patch: P,
  connection: SourceConnection | null | undefined,
): P {
  if (!connection) return patch
  const kept = Object.entries(patch).filter(([key]) => !(key in connection.defaults))
  return Object.fromEntries(kept) as P
}

/** The request fields of a source; a secret goes along only where the sign-in takes one. */
export function payloadUnder<P extends { sourceCredentials?: string }>(
  payload: P,
  fields: ConnectionFields,
): P {
  return fields.asksSecret ? payload : { ...payload, sourceCredentials: undefined }
}

/** Whether a library of this type may name its own address rather than a profile. */
export function ownAddressAllowed(descriptor: SourceTypeDescriptor): boolean {
  return !descriptor.profileRequired && descriptor.creatableWithOwnAddress
}
