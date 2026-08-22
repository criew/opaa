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
          set({ token: user.access_token, isAuthenticated: true })
        })
        userManager.events.addUserUnloaded(() => {
          set({ token: null, isAuthenticated: false })
        })
        userManager.events.addSilentRenewError((err) => {
          // Deliberately not resetting to a logged-out state here: the response interceptor
          // already retries the renew synchronously with the failing request. Logging keeps a
          // background failure (no request in flight yet) visible for troubleshooting.
          console.error('Silent token renew failed', err)
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

  getAccessToken: async () => get().token,

  renewToken: async () => {
    const { userManager, mode } = get()
    if (mode !== 'oidc' || !userManager) return false
    try {
      const user = await userManager.signinSilent()
      if (!user) return false
      set({ token: user.access_token, isAuthenticated: true })
      return true
    } catch {
      return false
    }
  },

  expireSession: () => {
    // Same store reset as logout() (#440), but deliberately without signoutRedirect(): the IdP
    // session must survive so a fresh signinRedirect() (or a manual reload) does not force the
    // user to re-enter credentials for what was just an access-token hiccup.
    resetAllStores()
    clearDevUser()
    set({
      token: null,
      user: null,
      isAuthenticated: false,
      error: null,
    })
  },
}))
