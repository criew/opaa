import { bumpSessionEpoch } from './sessionEpoch'

/** A zustand store whose state offers `reset()`. */
export interface SessionStore {
  getState: () => { reset: () => void }
}

const sessionStores = new Set<SessionStore>()

/**
 * Registers a store that caches data of the signed-in user's session; every sign-out, expired
 * session and account handover then resets it. Called once by the store's own module right
 * after `create()`, so a store is registered as soon as it holds any state. Device settings such as
 * `useUiStore` stay unregistered and survive a sign-out.
 */
export function registerSessionStore(store: SessionStore): void {
  sessionStores.add(store)
}

/** The registered stores, in registration order. */
export function registeredSessionStores(): readonly SessionStore[] {
  return [...sessionStores]
}

export function resetAllStores(): void {
  // Bumped before any reset() runs: an async action that captured the epoch earlier skips its
  // write-back once its await resolves (see sessionEpoch.ts).
  bumpSessionEpoch()
  sessionStores.forEach((store) => store.getState().reset())
}
