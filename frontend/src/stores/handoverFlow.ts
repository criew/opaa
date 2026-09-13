/**
 * What the OIDC callback hands the handover page (#1563, ADR-0033 Entscheidung 12): the provider's
 * access token and the handover code, for the one `redeem` call and nothing else.
 *
 * Deliberately a module variable rather than storage: the callback page and the handover page are
 * the same page load, so nothing has to survive a navigation here - and neither the code nor the
 * provider token is written anywhere a later session could read them. The code itself does cross
 * the provider redirect, but inside oidc-client-ts's own sign-in state, which the library removes
 * when it completes the callback.
 */
export interface PendingHandover {
  /** The raw handover code from the link. */
  code: string
  /** The access token the provider issued - held for the redemption and then dropped. */
  providerToken: string
}

let pending: PendingHandover | null = null

export function setPendingHandover(handover: PendingHandover): void {
  pending = handover
}

/**
 * Reads the pending handover without consuming it - a pure read, so a component may call it while
 * rendering and StrictMode's double render changes nothing. {@link clearPendingHandover} is what
 * drops it, once the redemption is under way.
 */
export function peekPendingHandover(): PendingHandover | null {
  return pending
}

export function clearPendingHandover(): void {
  pending = null
}

/**
 * The note that a handover redirect is under way in this tab (#1563). Unlike the two values above
 * it has to survive the trip to the provider, so it lives in `sessionStorage` - it is not a secret,
 * only a flag, and it is per tab like everything else of the flow.
 *
 * It carries two decisions of the return trip: the start-up must **not** restore the local session
 * (it is about to be revoked, and ADR-0033 Entscheidung 12 allows no authenticated call between the
 * callback and the redemption - not even a refresh with the old local token), and the callback page
 * must not navigate into the application before the handover page has had its turn.
 */
const IN_FLIGHT_KEY = 'opaa.handover.inFlight'

export function markHandoverInFlight(): void {
  try {
    sessionStorage.setItem(IN_FLIGHT_KEY, '1')
  } catch {
    // storage may be unavailable (private mode); the flow then simply takes the ordinary path
  }
}

export function isHandoverInFlight(): boolean {
  try {
    return sessionStorage.getItem(IN_FLIGHT_KEY) !== null
  } catch {
    return false
  }
}

export function clearHandoverInFlight(): void {
  try {
    sessionStorage.removeItem(IN_FLIGHT_KEY)
  } catch {
    // nothing to clear if it could never be written
  }
}
