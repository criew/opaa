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
  getAccessToken: () => string | null
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
        })
        set({ userManager })

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

  getAccessToken: () => get().token,
}))
