import type {
  ConnectionAuthMethod,
  ConnectionProfileOption,
  ConnectionProfileRef,
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
  /** The proxy every library on the profile is reached through; absent for none. */
  proxy?: string | null
  /** Whether the certificate check is skipped for every library on the profile. */
  insecureSsl?: boolean
}

/** The fields a profile sets for every library on it besides its defaults - never sent by one. */
const TRANSPORT_FIELDS = ['sourceProxy', 'sourceInsecureSsl'] as const

export function sourceConnectionOf(option: ConnectionProfileOption): SourceConnection {
  return {
    profileId: option.id,
    name: option.name,
    serverUrl: option.serverUrl,
    authMethod: option.authMethod,
    defaults: option.connectorDefaults ?? {},
    proxy: option.sourceProxy ?? null,
    insecureSsl: option.sourceInsecureSsl,
  }
}

/**
 * The profile of a stored library as its managers read it; `null` while the reference lacks what
 * the forms need (below MANAGER it names only id and name).
 */
export function sourceConnectionOfRef(ref: ConnectionProfileRef): SourceConnection | null {
  if (!ref.serverUrl || !ref.authMethod) return null
  return {
    profileId: ref.id,
    name: ref.name,
    serverUrl: ref.serverUrl,
    authMethod: ref.authMethod,
    defaults: ref.connectorDefaults ?? {},
    proxy: ref.sourceProxy ?? null,
    insecureSsl: Boolean(ref.sourceInsecureSsl),
  }
}

/** Whether `key` is a field the profile sets: one of its defaults, its proxy or its TLS switch. */
function setByProfile(key: string, connection: SourceConnection): boolean {
  return key in connection.defaults || (TRANSPORT_FIELDS as readonly string[]).includes(key)
}

/**
 * Whether a library signing in this way enters a secret of its own in the source form; client
 * credentials and a service account key lie with the profile.
 */
export function asksLibrarySecret(method: ConnectionAuthMethod): boolean {
  switch (method) {
    case 'PERSONAL_SECRET':
      return true
    case 'NONE':
    case 'OAUTH':
    case 'CLIENT_CREDENTIALS':
    case 'SERVICE_ACCOUNT_KEY':
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

/** The fields of a create, update, test or listing request that a profile may set. */
export interface FramedRequest {
  sourceProxy?: string | null
  sourceInsecureSsl?: boolean | null
  sourceCredentials?: string | null
  sourceSettings?: Record<string, unknown> | null
  query?: Record<string, unknown> | null
}

/**
 * What every source form derives from its context, by the same rules: with a profile the address
 * starts at the profile's server address, the secret field exists only for a sign-in that takes
 * one, and a setting the profile sets - a default, the proxy, the TLS switch - is shown read-only
 * with the profile's value and never sent.
 */
export interface ConnectionFields {
  connection: SourceConnection | null
  probe: ProbeScope
  /** A test or listing request of the form, scoped by `probe` and framed by the profile. */
  probeRequest: <R extends FramedRequest>(request: R) => R & ProbeScope
  asksSecret: boolean
  isFixed: (key: string) => boolean
  /** The hint under a fixed field; null without a profile. */
  fixedHint: string | null
  /** The hint at the proxy field and the TLS switch; null without a profile. */
  transportHint: string | null
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
  const asksSecret = connection === null || asksLibrarySecret(connection.authMethod)
  return {
    connection,
    probe,
    probeRequest: (request) => ({ ...framedBy(request, connection, asksSecret), ...probe }),
    asksSecret,
    isFixed: (key) => connection !== null && setByProfile(key, connection),
    fixedHint: connection && `Vom Zugang „${connection.name}“ vorgegeben.`,
    transportHint:
      connection && `Proxy und Zertifikatsprüfung gibt der Zugang „${connection.name}“ vor.`,
    addressHint:
      connection &&
      `Liegt unter der Server-Adresse des Zugangs „${connection.name}“: ${connection.serverUrl}`,
  }
}

/**
 * `values` as a form under `connection` shows them: an empty address takes the profile's server
 * address, and every field named like a fixed setting, the proxy and the TLS switch take the
 * profile's value. Without a profile the values are returned as they are.
 */
export function withConnection<V>(values: V, connection: SourceConnection | null | undefined): V {
  if (!connection || values === null || typeof values !== 'object') return values
  const shown: Record<string, unknown> = { ...(values as Record<string, unknown>) }
  if (shown.sourceUrl === '') shown.sourceUrl = connection.serverUrl
  for (const [key, value] of Object.entries(connection.defaults)) {
    if (key in shown) shown[key] = value
  }
  if ('sourceProxy' in shown) shown.sourceProxy = connection.proxy ?? ''
  if ('sourceInsecureSsl' in shown) shown.sourceInsecureSsl = Boolean(connection.insecureSsl)
  return shown as V
}

/** Whether `address` is `base` or lies below it (path segments, same scheme and host). */
export function addressUnder(address: string, base: string): boolean {
  const target = address.trim().toLowerCase()
  const root = base.trim().replace(/\/+$/, '').toLowerCase()
  return target === root || target.startsWith(`${root}/`)
}

/**
 * `address` moved from under `from` to under `to`, keeping the part below - as a switch of profile
 * moves it; `null` when it does not lie under `from`.
 */
export function rebaseAddress(address: string, from: string, to: string): string | null {
  if (!addressUnder(address, from)) return null
  const below = address.trim().slice(from.trim().replace(/\/+$/, '').length)
  return `${to.trim().replace(/\/+$/, '')}${below}`
}

/**
 * The address a stored library has once connected through `next`: its own if that lies under
 * `next`, else moved from under `previousServerUrl`; `null` when it lies under neither - then it
 * has to be entered anew.
 */
export function addressAfterSwitch(
  address: string | null | undefined,
  previousServerUrl: string | null | undefined,
  next: SourceConnection,
): string | null {
  if (!address) return null
  if (addressUnder(address, next.serverUrl)) return address.trim()
  return previousServerUrl ? rebaseAddress(address, previousServerUrl, next.serverUrl) : null
}

/**
 * `values` as they stand once the choice moves from `previous` to `next` (either `null` for the
 * own address): the fields the previous profile set fall back to `empty`, and an address that
 * does not lie under the next profile - or came from the previous one - is cleared together with
 * the `derived` fields the source read from it.
 */
export function switchConnection<V>(
  values: V,
  empty: V,
  previous: SourceConnection | null | undefined,
  next: SourceConnection | null | undefined,
  derived: readonly string[] = [],
): V {
  if (values === null || typeof values !== 'object') return values
  const reset: Record<string, unknown> = { ...(values as Record<string, unknown>) }
  const blank = empty as Record<string, unknown>
  if (previous) {
    for (const key of [...Object.keys(previous.defaults), ...TRANSPORT_FIELDS]) {
      if (key in reset) reset[key] = blank[key]
    }
  }
  const address = reset.sourceUrl
  if (typeof address === 'string' && address !== '') {
    const fromPrevious = previous ? addressUnder(address, previous.serverUrl) : false
    const outsideNext = next ? !addressUnder(address, next.serverUrl) : false
    if (fromPrevious || outsideNext) {
      reset.sourceUrl = ''
      for (const key of derived) {
        if (key in reset) reset[key] = blank[key]
      }
    }
  }
  return reset as V
}

/** `patch` without the fields a profile sets - those keep the profile's value. */
export function withoutFixed<P extends object>(
  patch: P,
  connection: SourceConnection | null | undefined,
): P {
  if (!connection) return patch
  const kept = Object.entries(patch).filter(([key]) => !setByProfile(key, connection))
  return Object.fromEntries(kept) as P
}

function withoutDefaults(
  settings: Record<string, unknown>,
  connection: SourceConnection,
): Record<string, unknown> {
  return Object.fromEntries(
    Object.entries(settings).filter(([key]) => !(key in connection.defaults)),
  )
}

/**
 * `request` as it may be sent under `connection`: no value for a setting the profile sets, no
 * proxy, the certificate check on, and a secret only where the sign-in takes one. The profile's
 * values apply on the server, so one changed there meanwhile cannot make the request differ.
 */
export function framedBy<R extends FramedRequest>(
  request: R,
  connection: SourceConnection | null | undefined,
  asksSecret: boolean,
): R {
  if (!connection) return request
  const framed: Record<string, unknown> = {
    ...(request as Record<string, unknown>),
    sourceProxy: undefined,
    sourceInsecureSsl: false,
  }
  if (!asksSecret) framed.sourceCredentials = undefined
  if (request.sourceSettings) {
    framed.sourceSettings = withoutDefaults(request.sourceSettings, connection)
  }
  if (request.query) framed.query = withoutDefaults(request.query, connection)
  return framed as R
}

/** The request fields of a source as a create or an update sends them under its profile. */
export function payloadUnder<P extends FramedRequest>(payload: P, fields: ConnectionFields): P {
  return framedBy(payload, fields.connection, fields.asksSecret)
}

/** Whether a library of this type may name its own address rather than a profile. */
export function ownAddressAllowed(descriptor: SourceTypeDescriptor): boolean {
  return !descriptor.profileRequired && descriptor.creatableWithOwnAddress
}
