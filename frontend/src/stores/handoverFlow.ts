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
