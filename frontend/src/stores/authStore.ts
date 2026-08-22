import { create } from 'zustand'
import { UserManager, WebStorageStateStore } from 'oidc-client-ts'
import type { AuthMode, AuthUser } from '../types/auth'
import { getAuthConfig, getMe } from '../services/authApi'
import { clearDevUser, resolveDevUser } from '../services/devAuth'
import { resetAllStores } from './resettableStores'

interface AuthState {
  mode: AuthMode | null
  user: AuthUser | null
  token: string | null
  isAuthenticated: boolean
  isLoading: boolean
  error: string | null
  userManager: UserManager | null

  initialize: () => Promise<void>
  loginOidc: () => Promise<void>
  handleOidcCallback: () => Promise<void>
  logout: () => Promise<void>
  getAccessToken: () => Promise<string | null>
  // #737: a single silent-renew attempt via the refresh token, used by the response interceptor
  // (apiInterceptors.ts) on a 401 before it retries the original request. Returns whether the
  // renew succeeded - no iframe involved, see the UserManager comment below.
  renewToken: () => Promise<boolean>
  // #737: the "second failure" branch of the 401 handling - resets local session state without
  // signoutRedirect(), which would also destroy the IdP session. Left for the OIDC case; a
  // deliberate logout button click still calls logout() above and its full signoutRedirect().
  expireSession: () => void
}

// #737 review (nit): module-scoped rather than store state - it is plumbing for renewToken()
// below, not UI-observable state, and a Zustand field would need its own reset wiring for no
// benefit (see resettableStores.ts, which this deliberately stays out of).
let inFlightRenew: Promise<boolean> | null = null

export const useAuthStore = create<AuthState>((set, get) => ({
  mode: null,
  user: null,
  token: null,
  isAuthenticated: false,
  isLoading: true,
  error: null,
  userManager: null,

  initialize: async () => {
    try {
      const config = await getAuthConfig()
      set({ mode: config.mode })

      if (config.mode === 'dev') {
        // No login and no token: the backend authenticates every request as the selected dev
        // user. The user is still fetched so the UI shows a real identity and system role.
        resolveDevUser()
        const me = await getMe(null)
        set({ user: me, isAuthenticated: true, isLoading: false })
        return
      }

      if (config.mode === 'oidc' && config.authority && config.clientId) {
        const userManager = new UserManager({
          authority: config.authority,
          client_id: config.clientId,
          redirect_uri: `${window.location.origin}/auth/callback`,
          post_logout_redirect_uri: window.location.origin,
          response_type: 'code',
          scope: 'openid profile email',
          userStore: new WebStorageStateStore({ store: sessionStorage }),
          // #737: oidc-client-ts renews the access token in the background via the refresh
          // token once it is close to expiring - explicit here rather than relying on the
          // library default, and *not* an iframe-based silent renew (automaticSilentRenew alone
          // never opens one; that only happens if code elsewhere calls signinSilent with an
          // iframe request type). An iframe renew would fail regardless: frontend/nginx.conf sets
          // `frame-ancestors 'none'`.
          automaticSilentRenew: true,
          accessTokenExpiringNotificationTimeInSeconds: 60,
        })
        set({ userManager })

        // #737: keep the store's token current for the lifetime of the session - the previous
        // code only ever captured it once, as a snapshot, at login/callback time (the two other
        // `set({ token: ... })` calls in this file). UserLoaded also fires after every
        // automatic silent renew, which is exactly the event that used to go unnoticed.
        userManager.events.addUserLoaded((user) => {
          set((state) => ({
            token: user.access_token,
            // #737 review: a background silent renew can still resolve after expireSession() has
            // reset the store (`user: null`) - flipping isAuthenticated back to true here would
            // half-reanimate a session expireSession() just tore down (the removeUser() call
            // below stops the timer for the *next* renewal, but one already in flight can still
            // land). Only join an already-known session back up; never start one from this event.
            isAuthenticated: state.user !== null ? true : state.isAuthenticated,
          }))
        })
        userManager.events.addUserUnloaded(() => {
          set({ token: null, isAuthenticated: false })
        })
        userManager.events.addSilentRenewError((err) => {
          // #737 review: never log the error object itself - oidc-client-ts's ErrorResponse
          // carries the full failed token request in its `form` field, including the
          // refresh_token (exchangeRefreshToken's request body). Only the message (and, for an
          // ErrorResponse, its OAuth error code) are safe to surface. Deliberately not resetting
          // to a logged-out state here: the response interceptor already retries the renew
          // synchronously with the failing request. Logging keeps a background failure (no
          // request in flight yet) visible for troubleshooting.
          const message = err instanceof Error ? err.message : String(err)
          console.error('Silent token renew failed', message)
        })

        const oidcUser = await userManager.getUser()
        if (oidcUser && !oidcUser.expired) {
          const me = await getMe(oidcUser.access_token)
          set({
            token: oidcUser.access_token,
            user: me,
            isAuthenticated: true,
            isLoading: false,
          })
        } else {
          set({ isLoading: false })
        }
        return
      }

      set({ isLoading: false })
    } catch {
      // Deliberately no fallback to an authenticated-looking state: a failing
      // /api/v1/auth/config used to leave the user in a signed-in-looking but entirely
      // non-functional UI, because the backend kept rejecting every request. Surfacing the
      // failure is the honest outcome.
      set({
        mode: null,
        user: null,
        token: null,
        isAuthenticated: false,
        isLoading: false,
        error: 'Die Authentifizierungskonfiguration konnte nicht geladen werden.',
      })
    }
  },

  loginOidc: async () => {
    const { userManager } = get()
    if (userManager) {
      await userManager.signinRedirect()
    }
  },

  handleOidcCallback: async () => {
    const { userManager } = get()
    if (!userManager) return
    try {
      const oidcUser = await userManager.signinRedirectCallback()
      const me = await getMe(oidcUser.access_token)
      set({
        token: oidcUser.access_token,
        user: me,
        isAuthenticated: true,
        isLoading: false,
      })
    } catch (err) {
      set({
        error: err instanceof Error ? err.message : 'OIDC-Rückmeldung fehlgeschlagen',
        isLoading: false,
      })
    }
  },

  logout: async () => {
    const { userManager, mode } = get()
    // Resets every store that caches data scoped to the signed-in user's session (#440) - see
    // resettableStores.ts for which stores that covers and why. Must run before
    // signoutRedirect below: that call navigates the browser away in OIDC mode, so anything
    // after it would practically never run.
    resetAllStores()
    if (mode === 'oidc' && userManager) {
      await userManager.signoutRedirect()
    }
    clearDevUser()
    set({
      token: null,
      user: null,
      isAuthenticated: false,
      error: null,
    })
  },

  // #737 review: read the token live from the UserManager rather than the store's own snapshot -
  // the store field is only ever caught up by the UserLoaded listener above, one tick after
  // oidc-client-ts itself already knows the renewed token, which is exactly the race the request
  // interceptor (apiInterceptors.ts) can lose against an in-flight renew. In dev mode there is no
  // userManager at all, so the (always-null) store token is the only thing to return.
  getAccessToken: async () => {
    const { userManager, mode, token } = get()
    if (mode === 'oidc' && userManager) {
      const user = await userManager.getUser()
      return user && !user.expired ? user.access_token : token
    }
    return token
  },

  // #737 review (nit): concurrent 401s from the background polls (indexingStore/documentStore)
  // used to each start their own signinSilent() call - harmless today, but a refresh-token-rotating
  // IdP would have the first grant invalidate the token for every other in-flight one. Sharing one
  // in-flight renew across callers removes the N-parallel-grants case entirely.
  renewToken: () => {
    const { userManager, mode } = get()
    if (mode !== 'oidc' || !userManager) return Promise.resolve(false)
    if (inFlightRenew) return inFlightRenew
    inFlightRenew = (async () => {
      try {
        const user = await userManager.signinSilent()
        if (!user) return false
        set({ token: user.access_token, isAuthenticated: true })
        return true
      } catch {
        return false
      } finally {
        inFlightRenew = null
      }
    })()
    return inFlightRenew
  },

  expireSession: () => {
    const { userManager } = get()
    // Same store reset as logout() (#440), but deliberately without signoutRedirect(): the IdP
    // session must survive so a fresh signinRedirect() (or a manual reload) does not force the
    // user to re-enter credentials for what was just an access-token hiccup.
    resetAllStores()
    clearDevUser()
    set({
      token: null,
      user: null,
      isAuthenticated: false,
      // #737 review: explain the redirect to the login page - it used to look like a random
      // logout with no explanation (error: null), because this is exactly the branch a
      // successfully-renewed-but-still-401ing request falls into (apiInterceptors.ts).
      error: 'Deine Sitzung ist abgelaufen. Bitte melde dich erneut an.',
    })
    // #737 review: also drop the local OIDC session - removeUser() fires UserUnloaded (redundant
    // with the reset above, harmless) and stops oidc-client-ts's automatic-silent-renew timer, so
    // a background renewal already scheduled cannot resurrect the session this just tore down.
    // Local-only: it clears the WebStorageStateStore entry in sessionStorage, not the IdP session
    // itself - a fresh signinRedirect() still won't force new credentials.
    void userManager?.removeUser()
  },
}))
