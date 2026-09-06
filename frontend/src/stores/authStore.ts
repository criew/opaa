import { create } from 'zustand'
import { UserManager, WebStorageStateStore } from 'oidc-client-ts'
import type { AuthMode, AuthUser, SignInProvider } from '../types/auth'
import { getAuthConfig, getMe, UnknownIssuerError } from '../services/authApi'
import { clearDevUser, resolveDevUser } from '../services/devAuth'
import type { SessionExpiredReason } from '../services/apiInterceptors'
import { notify } from './notificationStore'
import { resetAllStores } from './resettableStores'

/**
 * Sign-in with several identity providers (ADR-0025, Entscheidung 1 and 5): one UserManager per
 * enabled provider, all sharing the redirect URI `<origin>/auth/callback`. The provider of the
 * running flow - and of the active session afterwards - is remembered per tab in sessionStorage,
 * where oidc-client-ts keeps both its user and its sign-in state (PKCE verifier included) for
 * these managers; the provider used last is remembered in localStorage only as the suggestion
 * for the next sign-in. Everything token-related (renewal, 401 handling, logout) works on the
 * active session's manager.
 */
export const FLOW_PROVIDER_STORAGE_KEY = 'opaa.oidc.flowProvider'
export const LAST_PROVIDER_STORAGE_KEY = 'opaa.oidc.lastProvider'

export const CONFIG_UNAVAILABLE_MESSAGE =
  'Die Authentifizierungskonfiguration konnte nicht geladen werden.'
export const NO_PROVIDER_MESSAGE =
  'Es ist kein Identitätsanbieter für die Anmeldung verfügbar. Bitte wenden Sie sich an die Systemverwaltung.'
export const PROVIDER_GONE_MESSAGE =
  'Der gewählte Identitätsanbieter steht nicht mehr zur Verfügung. Bitte melden Sie sich über einen anderen Anbieter an.'
export const UNKNOWN_ISSUER_MESSAGE =
  'Der Identitätsanbieter Ihrer Anmeldung ist nicht mehr zugelassen. Bitte melden Sie sich erneut an.'
export const SESSION_EXPIRED_MESSAGE =
  'Ihre Sitzung ist abgelaufen. Bitte melden Sie sich erneut an.'
export const LOCAL_LOGOUT_MESSAGE =
  'Sie wurden nur in dieser Anwendung abgemeldet; die Sitzung beim Identitätsanbieter besteht möglicherweise weiter.'

export function signInFailedMessage(providerName: string, detail: string): string {
  return `Die Anmeldung bei ${providerName} konnte nicht gestartet werden: ${detail}`
}

interface AuthState {
  mode: AuthMode | null
  user: AuthUser | null
  token: string | null
  isAuthenticated: boolean
  isLoading: boolean
  /** A sign-in redirect is being prepared (discovery fetch) - the page shows it as such. */
  isSigningIn: boolean
  error: string | null
  /** The enabled providers in sign-in page order (oidc mode); empty otherwise. */
  providers: SignInProvider[]
  /** The manager of the active session or of the running sign-in flow; null before either. */
  userManager: UserManager | null
  /** Which of {@link providers} {@link userManager} belongs to. */
  activeProviderId: string | null

  initialize: () => Promise<void>
  /**
   * Starts the sign-in at `providerId` (default: the suggested provider). `switchAccount` sends
   * `prompt=login`, so the provider asks for credentials even with a running SSO session.
   */
  loginOidc: (providerId?: string, options?: { switchAccount?: boolean }) => Promise<void>
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
  expireSession: (reason?: SessionExpiredReason) => void
  /** The provider the sign-in page proposes: the one used last, else the default, else the first. */
  suggestedProvider: () => SignInProvider | null
}

// #737 review (nit): module-scoped rather than store state - it is plumbing for renewToken()
// below, not UI-observable state, and a Zustand field would need its own reset wiring for no
// benefit (see resettableStores.ts, which this deliberately stays out of).
let inFlightRenew: Promise<boolean> | null = null

// Module-scoped like inFlightRenew: the managers of the providers that are not the active one are
// plumbing for a sign-in that has not started, not UI state.
let userManagers: Map<string, UserManager> = new Map()

function readStorage(storage: Storage, key: string): string | null {
  try {
    return storage.getItem(key)
  } catch {
    return null
  }
}

function writeStorage(storage: Storage, key: string, value: string | null) {
  try {
    if (value === null) storage.removeItem(key)
    else storage.setItem(key, value)
  } catch {
    // storage may be unavailable (private mode); the flow then simply starts at the default
  }
}

export const useAuthStore = create<AuthState>((set, get) => {
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
      // #737: oidc-client-ts renews the access token in the background via the refresh
      // token once it is close to expiring - explicit here rather than relying on the
      // library default, and *not* an iframe-based silent renew (automaticSilentRenew alone
      // never opens one; that only happens if code elsewhere calls signinSilent with an
      // iframe request type). An iframe renew would fail regardless: frontend/nginx.conf sets
      // `frame-ancestors 'none'`.
      automaticSilentRenew: true,
      accessTokenExpiringNotificationTimeInSeconds: 60,
    })
    // #737: keep the store's token current for the lifetime of the session - UserLoaded also
    // fires after every automatic silent renew, which is exactly the event that used to go
    // unnoticed. Only the active manager ever loads a user, so listening on every manager is
    // harmless and saves re-wiring on activation.
    userManager.events.addUserLoaded((user) => {
      if (get().userManager !== userManager) return
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
      if (get().userManager !== userManager) return
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

  /** Drops the local session of the active manager (never the provider's own session). */
  function dropLocalSession() {
    const { userManager } = get()
    writeStorage(sessionStorage, FLOW_PROVIDER_STORAGE_KEY, null)
    void userManager?.removeUser()
  }

  return {
    mode: null,
    user: null,
    token: null,
    isAuthenticated: false,
    isLoading: true,
    isSigningIn: false,
    error: null,
    providers: [],
    userManager: null,
    activeProviderId: null,

    initialize: async () => {
      let config
      try {
        config = await getAuthConfig()
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
          error: CONFIG_UNAVAILABLE_MESSAGE,
        })
        return
      }
      const providers = config.providers ?? []
      set({ mode: config.mode, providers })

      if (config.mode === 'dev') {
        // No login and no token: the backend authenticates every request as the selected dev
        // user. The user is still fetched so the UI shows a real identity and system role.
        resolveDevUser()
        try {
          const me = await getMe(null)
          set({ user: me, isAuthenticated: true, isLoading: false })
        } catch {
          set({ isAuthenticated: false, isLoading: false, error: CONFIG_UNAVAILABLE_MESSAGE })
        }
        return
      }

      if (config.mode !== 'oidc') {
        set({ isLoading: false })
        return
      }

      userManagers = new Map(providers.map((p) => [p.id, createUserManager(p)]))
      // a suggestion for a provider that no longer exists would propose a dead end
      const last = readStorage(localStorage, LAST_PROVIDER_STORAGE_KEY)
      if (last && !userManagers.has(last)) {
        writeStorage(localStorage, LAST_PROVIDER_STORAGE_KEY, null)
      }
      if (providers.length === 0) {
        set({
          userManager: null,
          activeProviderId: null,
          isLoading: false,
          error: NO_PROVIDER_MESSAGE,
        })
        return
      }
      // the session (or the flow under way) belongs to the provider this tab remembers; a
      // provider disabled in the meantime is simply no longer there, and the tab starts over
      const remembered = readStorage(sessionStorage, FLOW_PROVIDER_STORAGE_KEY)
      const active = remembered && userManagers.has(remembered) ? activate(remembered, true) : null
      if (!active && remembered) {
        writeStorage(sessionStorage, FLOW_PROVIDER_STORAGE_KEY, null)
      }
      if (!active) {
        // no session and no flow under way: the suggested provider's manager stands ready,
        // so token plumbing (renew, expiry) has a manager to talk to from the first moment
        const suggested = get().suggestedProvider()
        if (suggested) activate(suggested.id, false)
      }
      const oidcUser = active ? await active.getUser() : null
      if (!oidcUser || oidcUser.expired) {
        set({ isLoading: false })
        return
      }
      try {
        const me = await getMe(oidcUser.access_token)
        set({ token: oidcUser.access_token, user: me, isAuthenticated: true, isLoading: false })
      } catch (err) {
        // the stored session is worthless when its provider was disabled - drop it, keep the
        // sign-in page armed with the providers just loaded
        dropLocalSession()
        set({
          token: null,
          user: null,
          isAuthenticated: false,
          isLoading: false,
          error:
            err instanceof UnknownIssuerError ? UNKNOWN_ISSUER_MESSAGE : SESSION_EXPIRED_MESSAGE,
        })
      }
    },

    suggestedProvider: () => {
      const { providers } = get()
      if (providers.length === 0) return null
      const last = readStorage(localStorage, LAST_PROVIDER_STORAGE_KEY)
      return (
        providers.find((p) => p.id === last) ?? providers.find((p) => p.isDefault) ?? providers[0]
      )
    },

    loginOidc: async (providerId, options) => {
      const chosen = providerId ?? get().suggestedProvider()?.id
      const provider = get().providers.find((p) => p.id === chosen)
      const userManager = chosen ? activate(chosen, true) : null
      if (!chosen || !provider || !userManager) {
        set({ error: PROVIDER_GONE_MESSAGE })
        return
      }
      writeStorage(localStorage, LAST_PROVIDER_STORAGE_KEY, chosen)
      set({ isSigningIn: true, error: null })
      try {
        await userManager.signinRedirect(options?.switchAccount ? { prompt: 'login' } : undefined)
      } catch (err) {
        // discovery unreachable (CSP not yet widened, DNS, provider down): say so instead of a
        // click that visibly does nothing
        writeStorage(sessionStorage, FLOW_PROVIDER_STORAGE_KEY, null)
        set({
          isSigningIn: false,
          error: signInFailedMessage(
            provider.displayName,
            err instanceof Error ? err.message : String(err),
          ),
        })
      }
    },

    handleOidcCallback: async () => {
      const { userManager, activeProviderId } = get()
      // the flow's provider is pinned per tab; without it (or with a provider disabled in the
      // meantime, whose manager was never built) there is nothing to complete the callback with
      const flowProvider = readStorage(sessionStorage, FLOW_PROVIDER_STORAGE_KEY)
      if (!userManager || !flowProvider || flowProvider !== activeProviderId) {
        set({ error: PROVIDER_GONE_MESSAGE, isLoading: false })
        return
      }
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
        if (err instanceof UnknownIssuerError) {
          dropLocalSession()
          set({ error: UNKNOWN_ISSUER_MESSAGE, isLoading: false })
          return
        }
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
      writeStorage(sessionStorage, FLOW_PROVIDER_STORAGE_KEY, null)
      if (mode === 'oidc' && userManager) {
        try {
          // the RP-initiated logout at the provider of the active session (ADR-0025)
          await userManager.signoutRedirect()
        } catch {
          // no end_session_endpoint at this provider, or it could not be reached: a local
          // sign-out is all there is, and the person is told exactly that
          await userManager.removeUser()
          notify(LOCAL_LOGOUT_MESSAGE, 'info')
        }
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

    expireSession: (reason) => {
      // Same store reset as logout() (#440), but deliberately without signoutRedirect(): the IdP
      // session must survive so a fresh signinRedirect() (or a manual reload) does not force the
      // user to re-enter credentials for what was just an access-token hiccup.
      resetAllStores()
      clearDevUser()
      // #737 review: also drop the local OIDC session - removeUser() fires UserUnloaded (redundant
      // with the reset below, harmless) and stops oidc-client-ts's automatic-silent-renew timer, so
      // a background renewal already scheduled cannot resurrect the session this just tore down.
      // Local-only: it clears the WebStorageStateStore entry in sessionStorage, not the IdP session
      // itself - a fresh signinRedirect() still won't force new credentials.
      dropLocalSession()
      set({
        token: null,
        user: null,
        isAuthenticated: false,
        // #737 review: explain the redirect to the login page - it used to look like a random
        // logout with no explanation (error: null), because this is exactly the branch a
        // successfully-renewed-but-still-401ing request falls into (apiInterceptors.ts).
        // ADR-0025: `unknown_issuer` is the provider of this session being disabled, not an
        // expired token - it gets its own explanation.
        error: reason === 'unknown_issuer' ? UNKNOWN_ISSUER_MESSAGE : SESSION_EXPIRED_MESSAGE,
      })
      if (reason === 'unknown_issuer') {
        // the provider list is stale by definition now: reload it, so the sign-in page neither
        // proposes nor lists the provider that was just refused (ADR-0025: no sign-in loop)
        const stale = get().activeProviderId
        if (stale && readStorage(localStorage, LAST_PROVIDER_STORAGE_KEY) === stale) {
          writeStorage(localStorage, LAST_PROVIDER_STORAGE_KEY, null)
        }
        void get()
          .initialize()
          .then(() => set({ error: UNKNOWN_ISSUER_MESSAGE }))
      }
    },
  }
})
