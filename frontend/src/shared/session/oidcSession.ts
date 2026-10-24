import { UserManager, WebStorageStateStore } from 'oidc-client-ts'
import type { SignInProvider } from './types'
import { clearSilentSignInFlow, markSilentSignInFlow } from './silentSignIn'
import { writeStorage } from '../utils/storage'
import { FLOW_PROVIDER_STORAGE_KEY } from './authState'
import type { AuthGet, AuthSet } from './authState'

// Module-scoped rather than store state: the managers of the providers that are not the active one
// are plumbing for a sign-in that has not started, not UI state.
let userManagers: Map<string, UserManager> = new Map()

export function createOidcSession(set: AuthSet, get: AuthGet) {
  /** One manager per provider, its session events wired exactly once. */
  function createUserManager(provider: SignInProvider): UserManager {
    const userManager = new UserManager({
      authority: provider.issuerUri,
      client_id: provider.clientId,
      redirect_uri: `${window.location.origin}/auth/callback`,
      post_logout_redirect_uri: window.location.origin,
      response_type: 'code',
      scope: 'openid profile email',
      userStore: new WebStorageStateStore({ store: sessionStorage }),
      // the sign-in state (PKCE verifier) stays in this tab too, next to the flow's provider
      stateStore: new WebStorageStateStore({ store: sessionStorage }),
      // oidc-client-ts renews the access token in the background via the refresh
      // token once it is close to expiring - explicit here rather than relying on the
      // library default, and *not* an iframe-based silent renew (automaticSilentRenew alone
      // never opens one; that only happens if code elsewhere calls signinSilent with an
      // iframe request type). An iframe renew would fail regardless: frontend/nginx.conf sets
      // `frame-ancestors 'none'`.
      automaticSilentRenew: true,
      accessTokenExpiringNotificationTimeInSeconds: 60,
    })
    // Keep the store's token current for the lifetime of the session - UserLoaded also
    // fires after every automatic silent renew. Only the active manager ever loads a user, so
    // listening on every manager is harmless and saves re-wiring on activation.
    userManager.events.addUserLoaded((user) => {
      if (get().userManager !== userManager) return
      set((state) => ({
        token: user.access_token,
        // A background silent renew can still resolve after expireSession() has
        // reset the store (`user: null`) - flipping isAuthenticated back to true here would
        // half-reanimate a session expireSession() just tore down (its removeUser() call
        // stops the timer for the *next* renewal, but one already in flight can still
        // land). Only join an already-known session back up; never start one from this event.
        isAuthenticated: state.user !== null ? true : state.isAuthenticated,
      }))
    })
    userManager.events.addUserUnloaded(() => {
      if (get().userManager !== userManager) return
      set({ token: null, isAuthenticated: false })
    })
    userManager.events.addSilentRenewError((err) => {
      // Never log the error object itself - oidc-client-ts's ErrorResponse
      // carries the full failed token request in its `form` field, including the
      // refresh_token (exchangeRefreshToken's request body). Only the message (and, for an
      // ErrorResponse, its OAuth error code) are safe to surface. Deliberately not resetting
      // to a logged-out state here: the response interceptor already retries the renew
      // synchronously with the failing request. Logging keeps a background failure (no
      // request in flight yet) visible for troubleshooting.
      const message = err instanceof Error ? err.message : String(err)
      console.error('Silent token renew failed', message)
    })
    return userManager
  }

  /**
   * Makes `providerId`'s manager the active one. `remember` pins the provider for this tab (a
   * flow started, a session found); the pre-activation of the suggested provider before any flow
   * leaves nothing behind.
   */
  function activate(providerId: string, remember: boolean): UserManager | null {
    const userManager = userManagers.get(providerId) ?? null
    if (!userManager) return null
    if (remember) writeStorage(sessionStorage, FLOW_PROVIDER_STORAGE_KEY, providerId)
    set({ userManager, activeProviderId: providerId })
    return userManager
  }

  /**
   * Opens a sign-in flow at `providerId`: its manager becomes the active one, the provider is
   * pinned for this tab, and the note of what kind of flow this is gets written - set for the
   * automatic attempt, cleared for every other. Written either way and in one place, so an
   * attempt that never came back cannot be read as belonging to the flow that follows and rob it
   * of its error message. Every `signinRedirect()` of this store starts here.
   *
   * Answers `null` for a provider this sign-in cannot go to - and writes nothing at all then. Both
   * conditions are checked before the first note: a manager whose provider row is gone (disabled
   * while the page stood open) is not a flow that starts, and a note written for a flow that never
   * starts is exactly what the callback of the *next* one would read as its own.
   */
  function beginSignInFlow(providerId: string, options?: { silent?: boolean }): UserManager | null {
    if (!get().providers.some((p) => p.id === providerId)) return null
    const userManager = activate(providerId, true)
    if (!userManager) return null
    if (options?.silent) markSilentSignInFlow()
    else clearSilentSignInFlow()
    return userManager
  }

  /** Drops the local session of the active manager (never the provider's own session). */
  function dropLocalSession() {
    const { userManager } = get()
    writeStorage(sessionStorage, FLOW_PROVIDER_STORAGE_KEY, null)
    void userManager?.removeUser()
  }

  /** Builds one manager per provider of the configuration just loaded, replacing all earlier ones. */
  function installUserManagers(providers: SignInProvider[]) {
    userManagers = new Map(providers.map((p) => [p.id, createUserManager(p)]))
  }

  function hasUserManager(providerId: string): boolean {
    return userManagers.has(providerId)
  }

  return { activate, beginSignInFlow, dropLocalSession, installUserManagers, hasUserManager }
}

export type OidcSession = ReturnType<typeof createOidcSession>
