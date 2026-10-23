import { apiErrorCode } from '../api/apiErrorDetails'

/**
 * The refusal of an operation that requires a recent sign-in (`@RequiresRecentLogin` in the
 * backend): `403` with this code, while the token's `auth_time` is missing or older than the
 * configured window. A refreshed token never counts.
 */
export const RECENT_LOGIN_REQUIRED = 'RECENT_LOGIN_REQUIRED'

export const RECENT_LOGIN_REQUIRED_MESSAGE =
  'Bitte bestätigen Sie zuerst Ihre Anmeldung, dann versuchen Sie es erneut.'

export const RECENT_LOGIN_NOT_CONFIRMED_BY_PROVIDER_MESSAGE =
  'Ihr Anmeldedienst hat die erneute Anmeldung nicht bestätigt. Bitte versuchen Sie es noch ' +
  'einmal; bleibt es dabei, wenden Sie sich an die Systemverwaltung.'

/** Whether a failed call was refused for want of a recent sign-in. */
export function isRecentLoginRequired(err: unknown): boolean {
  return apiErrorCode(err) === RECENT_LOGIN_REQUIRED
}

/**
 * The sentence for a refusal for want of a recent sign-in. Right after a forced sign-in at the
 * provider (see {@link consumeRecentLoginReturn}) it says that the provider did not confirm it -
 * a provider that ignores `prompt=login`/`max_age=0` leaves the old `auth_time` in place.
 */
export function recentLoginRefusalMessage(afterProviderSignIn: boolean): string {
  return afterProviderSignIn
    ? RECENT_LOGIN_NOT_CONFIRMED_BY_PROVIDER_MESSAGE
    : RECENT_LOGIN_REQUIRED_MESSAGE
}

/**
 * The note that this page load came back from a sign-in forced at the provider. A module variable
 * like the pending handover: the callback page and the page it returns to are the same page load,
 * and a reload is no confirmation.
 */
let returnedFromProviderSignIn = false

export function markRecentLoginReturn(): void {
  returnedFromProviderSignIn = true
}

/**
 * Whether this page load came back from a sign-in forced at the provider, answered once: the page
 * that asked for it continues the operation (or explains a refusal with
 * {@link recentLoginRefusalMessage}), and a later visit starts over.
 */
export function consumeRecentLoginReturn(): boolean {
  const returned = returnedFromProviderSignIn
  returnedFromProviderSignIn = false
  return returned
}
