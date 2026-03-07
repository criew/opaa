import { create } from 'zustand'
import { UserManager, WebStorageStateStore } from 'oidc-client-ts'
import type { AuthMode, AuthUser } from '../types/auth'
import { getAuthConfig, login as apiLogin, getMe } from '../services/authApi'

interface AuthState {
  mode: AuthMode | null
  user: AuthUser | null
  token: string | null
  isAuthenticated: boolean
  isLoading: boolean
  error: string | null
  userManager: UserManager | null

  initialize: () => Promise<void>
  loginBasic: (username: string, password: string) => Promise<void>
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

      if (config.mode === 'mock') {
        set({ isAuthenticated: true, isLoading: false })
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
      set({ mode: 'mock', isAuthenticated: true, isLoading: false })
    }
  },

  loginBasic: async (username: string, password: string) => {
    set({ isLoading: true, error: null })
    try {
      const response = await apiLogin({ username, password })
      const me = await getMe(response.accessToken)
      set({
        token: response.accessToken,
        user: me,
        isAuthenticated: true,
        isLoading: false,
      })
    } catch (err) {
      set({
        error: err instanceof Error ? err.message : 'Login failed',
        isLoading: false,
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
        error: err instanceof Error ? err.message : 'OIDC callback failed',
        isLoading: false,
      })
    }
  },

  logout: async () => {
    const { userManager, mode } = get()
    if (mode === 'oidc' && userManager) {
      await userManager.signoutRedirect()
    }
    set({
      token: null,
      user: null,
      isAuthenticated: false,
      error: null,
    })
  },

  getAccessToken: () => get().token,
}))
