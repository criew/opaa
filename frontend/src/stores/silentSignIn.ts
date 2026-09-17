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
 * The same note in module scope, for the one case in which the written one cannot be: where
 * `sessionStorage` is unavailable (private mode) the single attempt this tab has would otherwise
 * start over on every render of the sign-in page. A redirect cannot even get under way there -
 * oidc-client-ts keeps its PKCE verifier in that same storage - but this guard must not rest on
 * that. Where the storage answers, it is the only source of truth.
 */
let spentInThisPageLoad = false

/** Whether the note is set; `null` where the storage itself is unavailable. */
function read(key: string): boolean | null {
  try {
    return sessionStorage.getItem(key) !== null
  } catch {
    return null
  }
}

function write(key: string, set: boolean): void {
  try {
    if (set) sessionStorage.setItem(key, '1')
    else sessionStorage.removeItem(key)
  } catch {
    // storage may be unavailable (private mode); see the module comment above
  }
}

/**
 * Spends this tab's one automatic attempt. Every sign-in of this tab does so, whether it started
 * automatically or by a click: once a person has begun a sign-in, coming back to the sign-in page
 * must show them that page - with its error, its other providers, its local mask - instead of
 * sending them off again.
 */
export function spendSilentSignIn(): void {
  spentInThisPageLoad = true
  write(SPENT_KEY, true)
}

export function isSilentSignInSpent(): boolean {
  return read(SPENT_KEY) ?? spentInThisPageLoad
}

export function markSilentSignInFlow(): void {
  write(FLOW_KEY, true)
}

export function isSilentSignInFlow(): boolean {
  return read(FLOW_KEY) === true
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
  if (typeof err !== 'object' || err === null || !('error' in err)) return false
  const code = (err as { error: unknown }).error
  return typeof code === 'string' && code.length > 0
}
