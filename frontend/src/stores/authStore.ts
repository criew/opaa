import { create } from 'zustand'
import { UserManager, WebStorageStateStore } from 'oidc-client-ts'
import type {
  AuthMode,
  AuthUser,
  LocalAccountsConfig,
  LocalTokenResponse,
  PasswordChangeReason,
  SessionKind,
  SignInProvider,
} from '../types/auth'
import { LOCAL_ACCOUNTS_DISABLED } from '../types/auth'
import {
  changePassword as changePasswordRequest,
  describeLocalSignInFailure,
  endsSession,
  forgetLocalSession,
  getAuthConfig,
  getMe,
  loginLocal as loginLocalRequest,
  logoutLocal,
  performLocalRefresh,
  rememberLocalSession,
  UnknownIssuerError,
} from '../services/authApi'
import { clearDevUser, resolveDevUser } from '../services/devAuth'
import type { SessionExpiredReason } from '../services/apiInterceptors'
import { sessionEndingReason } from '../services/apiInterceptors'
import {
  CONFIG_UNAVAILABLE_MESSAGE,
  LOCAL_LOGOUT_MESSAGE,
  LOCAL_SIGN_IN_FAILED_MESSAGE,
  LOCAL_SIGN_IN_INCOMPLETE_MESSAGE,
  LOCAL_SIGN_IN_UNREACHABLE_MESSAGE,
  NO_PROVIDER_MESSAGE,
  PROVIDER_GONE_MESSAGE,
  SESSION_EXPIRED_MESSAGE,
  SESSION_UNAVAILABLE_MESSAGE,
  UNKNOWN_ISSUER_MESSAGE,
  sessionEndMessage,
  signInFailedMessage,
  tooManyAttemptsMessage,
} from '../utils/authMessages'
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
 *
 * A tab holds at most one session, and its kind lives in this store only (ADR-0033): a local
 * session keeps its access token in memory - never in localStorage - and is restored after a
 * reload through the HttpOnly refresh cookie, gated by the non-secret note
 * `opaa.auth.lastSessionKind` (see authApi.ts).
 */
export const FLOW_PROVIDER_STORAGE_KEY = 'opaa.oidc.flowProvider'
export const LAST_PROVIDER_STORAGE_KEY = 'opaa.oidc.lastProvider'

export {
  CONFIG_UNAVAILABLE_MESSAGE,
  LOCAL_LOGOUT_MESSAGE,
  LOCAL_SIGN_IN_FAILED_MESSAGE,
  NO_PROVIDER_MESSAGE,
  PROVIDER_GONE_MESSAGE,
  SESSION_EXPIRED_MESSAGE,
  UNKNOWN_ISSUER_MESSAGE,
  signInFailedMessage,
}

/**
 * Why a session that looked established could not be taken up: the backend names the cause in the
 * challenge of its refusal (ADR-0033, Entscheidung 8) - a locked account or a revoked session is
 * not the same as a token that merely expired, and the person is told which it was.
 */
function sessionSetupFailureMessage(err: unknown, fallback: string): string {
  if (err instanceof UnknownIssuerError) return UNKNOWN_ISSUER_MESSAGE
  const reason = sessionEndingReason(err)
  return reason ? sessionEndMessage(reason) : fallback
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
  /** What local accounts offer right now (ADR-0033); switched off until the config says otherwise. */
  localAccounts: LocalAccountsConfig
  /** The kind of the active session, or of the sign-in under way; null while signed out. */
  sessionKind: SessionKind | null
  /** The account must set a new password before any route but /account/password answers. */
  passwordChangeRequired: boolean
  /** Why the change is demanded - the sentence the password page shows. */
  passwordChangeReason: PasswordChangeReason | null

  initialize: () => Promise<void>
  /**
   * Starts the sign-in at `providerId` (default: the suggested provider). `switchAccount` sends
   * `prompt=login`, so the provider asks for credentials even with a running SSO session.
   */
  loginOidc: (providerId?: string, options?: { switchAccount?: boolean }) => Promise<void>
  handleOidcCallback: () => Promise<void>
  /**
   * Signs in with an account of this installation. Returns whether it worked; the reason of a
   * refusal is in {@link AuthState.error}, in one wording for every refused sign-in.
   */
  loginLocal: (email: string, password: string) => Promise<boolean>
  /**
   * Changes the password of the signed-in local account and adopts the session the backend mints
   * in the same answer, which clears {@link AuthState.passwordChangeRequired}.
   */
  changePassword: (currentPassword: string, newPassword: string) => Promise<void>
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
  /** The 403 of the forced password change (ADR-0033, Entscheidung 8), from the interceptor. */
  requirePasswordChange: (reason: PasswordChangeReason | null) => void
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

// When the access token of the local session stops being valid (epoch ms). Plumbing for logout,
// which may only present a bearer the resource server still accepts; a UI never reads it.
let localTokenExpiresAt: number | null = null

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

  /**
   * Takes over a freshly minted local session. A token that demands a password change reaches
   * nothing but `/api/v1/auth/local/*` - `/auth/me` included - so the identity is fetched only
   * once the new password stands (ADR-0033, Entscheidung 8).
   */
  async function adoptLocalSession(tokens: LocalTokenResponse) {
    rememberLocalSession()
    localTokenExpiresAt = Date.now() + tokens.expiresInSeconds * 1000
    const session = {
      token: tokens.accessToken,
      sessionKind: 'local' as const,
      isAuthenticated: true,
      isLoading: false,
      isSigningIn: false,
      error: null,
      passwordChangeRequired: tokens.passwordChangeRequired,
      passwordChangeReason: tokens.passwordChangeReason ?? null,
    }
    if (tokens.passwordChangeRequired) {
      set({ ...session, user: null })
      return
    }
    const me = await getMe(tokens.accessToken)
    set({ ...session, user: me })
  }

  /** Forgets the local access token of this tab. The browser-wide note stays untouched. */
  function dropLocalTokens() {
    localTokenExpiresAt = null
  }

  /**
   * Ends the local session of this browser: the tab's token and the note that gates the start-up
   * refresh. Only for a session that is actually over - a backend that was briefly unreachable
   * must keep the note, or no later reload would even try to restore the session, and a second tab
   * would lose its renewal at the next 401.
   */
  function endLocalSession() {
    localTokenExpiresAt = null
    forgetLocalSession()
  }

  /**
   * One attempt at the local session behind the refresh cookie. `none` means there is nothing to
   * restore and the caller carries on; `restored` and `ended` both mean the outcome - including
   * its explanation - is already in the store.
   */
  async function restoreLocalSession(): Promise<'none' | 'restored' | 'ended'> {
    const tokens = await performLocalRefresh()
    if (!tokens) return 'none'
    try {
      await adoptLocalSession(tokens)
      return 'restored'
    } catch (err) {
      // The refresh worked, so a session existed; whatever refused the identity call names its
      // own cause (locked, expired, management switched off) and that is what the person reads.
      // A 5xx or a network error is not such a cause: the session stays on record for the next try.
      const over = endsSession(err)
      if (over) endLocalSession()
      else dropLocalTokens()
      set({
        token: null,
        user: null,
        isAuthenticated: false,
        sessionKind: null,
        isLoading: false,
        error: sessionSetupFailureMessage(
          err,
          over ? SESSION_EXPIRED_MESSAGE : SESSION_UNAVAILABLE_MESSAGE,
        ),
      })
      return 'ended'
    }
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
    localAccounts: LOCAL_ACCOUNTS_DISABLED,
    sessionKind: null,
    passwordChangeRequired: false,
    passwordChangeReason: null,

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
      // getAuthConfig() already substitutes the switched-off default for a missing block.
      set({ mode: config.mode, providers, localAccounts: config.localAccounts })

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
      if (oidcUser && !oidcUser.expired) {
        try {
          const me = await getMe(oidcUser.access_token)
          set({
            token: oidcUser.access_token,
            user: me,
            isAuthenticated: true,
            isLoading: false,
            sessionKind: 'oidc',
          })
        } catch (err) {
          // the stored session is worthless when its provider was disabled - drop it, keep the
          // sign-in page armed with the providers just loaded
          dropLocalSession()
          set({
            token: null,
            user: null,
            isAuthenticated: false,
            isLoading: false,
            error: sessionSetupFailureMessage(err, SESSION_EXPIRED_MESSAGE),
          })
        }
        return
      }
      // No provider session in this tab: one attempt at a local one (ADR-0033). Without the note
      // of an earlier local session nothing is restored and no request is made, so a regular OIDC
      // sign-in never sees a failed call it did not ask for.
      if ((await restoreLocalSession()) !== 'none') return
      if (providers.length === 0 && !get().localAccounts.enabled) {
        set({
          userManager: null,
          activeProviderId: null,
          isLoading: false,
          error: NO_PROVIDER_MESSAGE,
        })
        return
      }
      set({ isLoading: false })
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
      set({ isSigningIn: true, sessionKind: 'oidc', error: null })
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

    loginLocal: async (email, password) => {
      set({ isSigningIn: true, error: null })
      let tokens
      try {
        tokens = await loginLocalRequest(email, password)
      } catch (err) {
        dropLocalTokens()
        const { status, retryAfterSeconds } = describeLocalSignInFailure(err)
        // ADR-0033, Entscheidung 9: every refusal - unknown address, wrong password, locked,
        // expired, switched off - is the same sentence; only the rate limit and an unreachable
        // backend say something else, because those name a next step.
        set({
          isSigningIn: false,
          error:
            status === 429
              ? tooManyAttemptsMessage(retryAfterSeconds)
              : status === null
                ? LOCAL_SIGN_IN_UNREACHABLE_MESSAGE
                : LOCAL_SIGN_IN_FAILED_MESSAGE,
        })
        return false
      }
      try {
        await adoptLocalSession(tokens)
        return true
      } catch (err) {
        // The credentials were right - the identity call was not. Saying "check e-mail address and
        // password" here would send the person after a mistake they did not make, and a backend
        // that was briefly unreachable must not cost another tab its session note either.
        if (endsSession(err)) endLocalSession()
        else dropLocalTokens()
        set({
          isSigningIn: false,
          isAuthenticated: false,
          token: null,
          user: null,
          sessionKind: null,
          error: sessionSetupFailureMessage(err, LOCAL_SIGN_IN_INCOMPLETE_MESSAGE),
        })
        return false
      }
    },

    changePassword: async (currentPassword, newPassword) => {
      const tokens = await changePasswordRequest(get().token, currentPassword, newPassword)
      await adoptLocalSession(tokens)
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
          sessionKind: 'oidc',
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
      const { userManager, mode, sessionKind, token } = get()
      // Resets every store that caches data scoped to the signed-in user's session (#440) - see
      // resettableStores.ts for which stores that covers and why. Must run before
      // signoutRedirect below: that call navigates the browser away in OIDC mode, so anything
      // after it would practically never run.
      resetAllStores()
      writeStorage(sessionStorage, FLOW_PROVIDER_STORAGE_KEY, null)
      if (sessionKind === 'local') {
        const stillValid = localTokenExpiresAt !== null && Date.now() < localTokenExpiresAt
        endLocalSession()
        try {
          // Revokes the refresh family and the presented access token at once (ADR-0033,
          // Entscheidung 7); a failure must not leave the tab signed in, so the local state is
          // reset either way and ProtectedRoute takes over from there.
          await logoutLocal(stillValid ? token : null)
        } catch {
          notify(LOCAL_LOGOUT_MESSAGE, 'info')
        }
        set({
          token: null,
          user: null,
          isAuthenticated: false,
          sessionKind: null,
          passwordChangeRequired: false,
          passwordChangeReason: null,
          error: null,
        })
        return
      }
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
      // Deliberately only this tab's token: a provider sign-out says nothing about a local session
      // another tab of the same browser may be holding.
      dropLocalTokens()
      set({
        token: null,
        user: null,
        isAuthenticated: false,
        sessionKind: null,
        passwordChangeRequired: false,
        passwordChangeReason: null,
        error: null,
      })
    },

    // #737 review: read the token live from the UserManager rather than the store's own snapshot -
    // the store field is only ever caught up by the UserLoaded listener above, one tick after
    // oidc-client-ts itself already knows the renewed token, which is exactly the race the request
    // interceptor (apiInterceptors.ts) can lose against an in-flight renew. In dev mode there is no
    // userManager at all, so the (always-null) store token is the only thing to return.
    getAccessToken: async () => {
      const { userManager, mode, token, sessionKind } = get()
      // The local access token lives in the store and nowhere else (ADR-0033, Entscheidung 7).
      if (sessionKind === 'local') return token
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
    renewToken: async () => {
      const { userManager, mode, sessionKind } = get()
      if (sessionKind === 'local') {
        const tokens = await performLocalRefresh()
        if (!tokens) return false
        localTokenExpiresAt = Date.now() + tokens.expiresInSeconds * 1000
        set({
          token: tokens.accessToken,
          isAuthenticated: true,
          passwordChangeRequired: tokens.passwordChangeRequired,
          passwordChangeReason: tokens.passwordChangeReason ?? null,
        })
        return true
      }
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
      // Only a local session gives up the browser-wide note; a provider session ending here says
      // nothing about a local session in another tab.
      if (get().sessionKind === 'local') endLocalSession()
      else dropLocalTokens()
      set({
        token: null,
        user: null,
        isAuthenticated: false,
        sessionKind: null,
        passwordChangeRequired: false,
        passwordChangeReason: null,
        // #737 review: explain the redirect to the login page - it used to look like a random
        // logout with no explanation (error: null), because this is exactly the branch a
        // successfully-renewed-but-still-401ing request falls into (apiInterceptors.ts).
        // ADR-0025/ADR-0033: every marker of the challenge has its own sentence - a disabled
        // provider, a locked account, a revoked session are not an expired token.
        error: sessionEndMessage(reason),
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

    requirePasswordChange: (reason) => {
      set({ passwordChangeRequired: true, passwordChangeReason: reason })
    },
  }
})
