import type { AccountResponse } from '../../../types/api'

/** The origin column's word for a local account. */
export const LOCAL_ORIGIN_LABEL = 'Lokal'

/**
 * An account under an issuer that has no provider row: one whose provider was deleted (the account
 * remains, ADR-0025) and, in the dev mode, every account of the synthetic dev issuer. The word says
 * only what is known - that no provider row carries this issuer - and the tooltip names the issuer.
 */
export const PROVIDER_UNKNOWN_LABEL = 'Kein Anbieter'

/** The placeholder of a cell that has no value for an identity-provider account. */
export const NOT_APPLICABLE = '–'

export function isLocalAccount(account: AccountResponse): boolean {
  return account.providerType === 'LOCAL'
}

/** „Lokal", the provider's display name, or the note that no provider row carries the issuer. */
export function accountOriginLabel(account: AccountResponse): string {
  if (isLocalAccount(account)) return LOCAL_ORIGIN_LABEL
  return account.provider?.displayName ?? PROVIDER_UNKNOWN_LABEL
}

/**
 * The state column of an identity-provider account: its lifecycle lies with the provider, so the
 * cell says where - and, when that provider no longer issues tokens, that nobody gets in this way
 * any more. Deliberately not the origin's word again: the two cells carry two statements.
 */
export function providerStateText(account: AccountResponse): string {
  if (!account.provider) return 'Anmeldung nicht möglich'
  return account.provider.enabled ? 'Beim Anbieter' : 'Anbieter deaktiviert'
}

/** Why a provider account cannot sign in - the tooltip of the two unusual states above. */
export function providerStateHint(account: AccountResponse): string | null {
  if (!account.provider) {
    return `Zum Issuer ${account.issuer} gibt es keine Anbieterzeile mehr; Tokens dieses Anbieters werden abgewiesen. Das Konto bleibt mit seinen Inhalten bestehen.`
  }
  if (!account.provider.enabled) {
    return `Der Identitätsanbieter „${account.provider.displayName}“ ist deaktiviert; über ihn meldet sich niemand mehr an. Das Konto bleibt mit seinen Inhalten bestehen.`
  }
  return null
}

export function roleManagedByProviderText(account: AccountResponse): string {
  const name = account.provider?.displayName
  return name
    ? `Die Rolle wird vom Identitätsanbieter „${name}“ geführt und kann hier nicht geändert werden.`
    : 'Die Rolle wird vom Identitätsanbieter geführt und kann hier nicht geändert werden.'
}

export function providerLifecycleHint(account: AccountResponse): string {
  const name = account.provider?.displayName
  return name
    ? `Sperren, Befristen und Löschen erfolgen beim Identitätsanbieter „${name}“.`
    : 'Sperren, Befristen und Löschen erfolgen beim Identitätsanbieter.'
}
