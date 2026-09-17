/**
 * The automatic sign-in of a person whose provider session is still running (#1631): the sign-in
 * page starts one authorization redirect with `prompt=none`, which the provider answers either with
 * a session or with a refusal - and never with a screen of its own.
 *
 * Deliberately a full-page redirect, not `signinSilent()`: an iframe against the provider is
 * unreachable here, because `frontend/nginx.conf` sets `frame-ancestors 'none'` and has no
 * `frame-src` at all (ADR-0005). Both notes therefore have to survive the trip to the provider and
 * live in `sessionStorage`, per tab like everything else of the flow; neither is a secret.
 */

/** One automatic attempt per tab; a second one would repeat a refusal the person already has. */
const SPENT_KEY = 'opaa.oidc.silentSignInSpent'

/** Whether the redirect currently under way is the automatic one rather than a deliberate one. */
const FLOW_KEY = 'opaa.oidc.silentSignInFlow'

/**
 * The stand-in for a note that could not be written. A storage that answers reads and refuses
 * writes is a state of its own - a full quota, a browser set to block site data - and it is not
 * the same as one that throws on every call: reading it back would say "not spent" forever. The
 * sign-in page would then wait on a redirect that already failed, every tile disabled and no way
 * on. Set only where the write really did not stick, so the written note stays the single source
 * of truth everywhere else.
 */
let spentWithoutStorage = false

function read(key: string): boolean {
  try {
    return sessionStorage.getItem(key) !== null
  } catch {
    return false
  }
}

/** Whether the note stuck; `false` where the storage refused it or is not there at all. */
function write(key: string, set: boolean): boolean {
  try {
    if (set) sessionStorage.setItem(key, '1')
    else sessionStorage.removeItem(key)
    return true
  } catch {
    return false
  }
}

/**
 * Spends this tab's one automatic attempt. Every sign-in of this tab does so, whether it started
 * automatically or by a click: once a person has begun a sign-in, coming back to the sign-in page
 * must show them that page - with its error, its other providers, its local mask - instead of
 * sending them off again.
 */
export function spendSilentSignIn(): void {
  if (!write(SPENT_KEY, true)) spentWithoutStorage = true
}

export function isSilentSignInSpent(): boolean {
  return spentWithoutStorage || read(SPENT_KEY)
}

export function markSilentSignInFlow(): void {
  write(FLOW_KEY, true)
}

export function isSilentSignInFlow(): boolean {
  return read(FLOW_KEY)
}

export function clearSilentSignInFlow(): void {
  write(FLOW_KEY, false)
}

/**
 * Whether the provider turned the authorization request down, as opposed to something going wrong
 * on the way there: oidc-client-ts reports a refusal as an `ErrorResponse` carrying the OAuth error
 * code. A `prompt=none` request without a session to reuse is answered with `login_required`,
 * `interaction_required`, `consent_required` or `account_selection_required` (OIDC Core 3.1.2.6);
 * any other code a provider may return - a client it does not know, a redirect URI it does not
 * accept - is the same kind of answer and reads the same way to the person: an attempt they never
 * asked for, and a sign-in page that simply stands there.
 */
export function isAuthorizationRefusal(err: unknown): boolean {
  return authorizationErrorCode(err) !== null
}

/** The OAuth error code of a refusal, for the log line that keeps it from going unnoticed. */
export function authorizationErrorCode(err: unknown): string | null {
  if (typeof err !== 'object' || err === null || !('error' in err)) return null
  const code = (err as { error: unknown }).error
  return typeof code === 'string' && code.length > 0 ? code : null
}
