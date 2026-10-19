import { logoutLocal, performLocalRefresh } from './authApi'
import { clearDevUser } from './devAuth'
import { LOCAL_LOGOUT_MESSAGE, UNKNOWN_ISSUER_MESSAGE, sessionEndMessage } from './authMessages'
import { spendSilentSignIn } from './silentSignIn'
import { notify } from '../stores/notificationStore'
import { resetAllStores } from './resettableStores'
import { readStorage, writeStorage } from '../utils/storage'
import { FLOW_PROVIDER_STORAGE_KEY, LAST_PROVIDER_STORAGE_KEY } from './authState'
import type { AuthState, AuthStoreContext } from './authState'

// Module-scoped rather than store state - it is plumbing for renewToken()
// below, not UI-observable state, and a Zustand field would need its own reset wiring for no
// benefit (see resettableStores.ts, which this deliberately stays out of).
let inFlightRenew: Promise<boolean> | null = null

export function createSessionEndActions(
  ctx: AuthStoreContext,
): Pick<
  AuthState,
  'logout' | 'getAccessToken' | 'renewToken' | 'expireSession' | 'requirePasswordChange'
> {
  const { set, get, oidc, local } = ctx
  return {
    logout: async () => {
      const { userManager, mode, sessionKind, token } = get()
      // First, before anything below can end the session - signoutRedirect() removes the user
      // before it even looks for an end_session_endpoint, and a 401 while this waits on the server
      // ends it through expireSession(). ProtectedRoute would otherwise record the page left behind
      // as a return target in that very render.
      set({ signedOut: true })
      // Resets every store registered via registerSessionStore(). Must run before
      // signoutRedirect below: that call navigates the browser away in OIDC mode, so anything
      // after it would practically never run.
      resetAllStores()
      writeStorage(sessionStorage, FLOW_PROVIDER_STORAGE_KEY, null)
      // Somebody who has just signed out wants the sign-in page, not the next session. The
      // provider session of an OIDC sign-out is over anyway; a local one says nothing about it.
      spendSilentSignIn()
      if (sessionKind === 'local') {
        const stillValid = local.isLocalTokenStillValid()
        local.endLocalSession()
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
          signedOut: true,
        })
        await get().refreshPublicAuthConfig()
        return
      }
      let redirectedToProvider = false
      if (mode === 'oidc' && userManager) {
        set({ isSigningOut: true })
        try {
          // the RP-initiated logout at the provider of the active session (ADR-0025)
          await userManager.signoutRedirect()
          redirectedToProvider = true
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
      local.dropLocalTokens()
      set({
        token: null,
        user: null,
        isAuthenticated: false,
        sessionKind: null,
        passwordChangeRequired: false,
        passwordChangeReason: null,
        error: null,
        signedOut: true,
        // signoutRedirect() resolves once the navigation started, while this page still shows -
        // only the fallback without a provider redirect may reveal the login page now
        isSigningOut: redirectedToProvider,
      })
      // After a signoutRedirect() the page is gone anyway; this is reached on the
      // path where the provider has no end_session_endpoint and the application stays.
      await get().refreshPublicAuthConfig()
    },

    // Read the token live from the UserManager rather than the store's own snapshot -
    // the store field is only ever caught up by the UserLoaded listener in oidcSession.ts, one tick after
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

    // Concurrent 401s from the background polls (indexingStore/documentStore) share one in-flight
    // renew: a refresh-token-rotating IdP would otherwise have the first grant invalidate the
    // token for every other in-flight signinSilent() call.
    renewToken: async () => {
      const { userManager, mode, sessionKind } = get()
      if (sessionKind === 'local') {
        const tokens = await performLocalRefresh()
        if (!tokens) return false
        local.recordLocalTokenExpiry(tokens.expiresInSeconds)
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
      // Same store reset as logout(), but deliberately without signoutRedirect(): the IdP
      // session must survive so a fresh signinRedirect() (or a manual reload) does not force the
      // user to re-enter credentials for what was just an access-token hiccup.
      resetAllStores()
      clearDevUser()
      // The session ended for a reason, and that sentence is on the sign-in page next. An
      // automatic redirect would carry it away before anybody read it.
      spendSilentSignIn()
      // Also drop the local OIDC session - removeUser() fires UserUnloaded (redundant
      // with the reset below, harmless) and stops oidc-client-ts's automatic-silent-renew timer, so
      // a background renewal already scheduled cannot resurrect the session this just tore down.
      // Local-only: it clears the WebStorageStateStore entry in sessionStorage, not the IdP session
      // itself - a fresh signinRedirect() still won't force new credentials.
      oidc.dropLocalSession()
      // Only a local session gives up the browser-wide note; a provider session ending here says
      // nothing about a local session in another tab.
      if (get().sessionKind === 'local') local.endLocalSession()
      else local.dropLocalTokens()
      set({
        token: null,
        user: null,
        isAuthenticated: false,
        sessionKind: null,
        passwordChangeRequired: false,
        passwordChangeReason: null,
        // Explain the redirect to the login page - without it this looks like a random logout,
        // because this is exactly the branch a successfully-renewed-but-still-401ing request falls
        // into (apiInterceptors.ts).
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
      } else {
        // The same reason as on logout: if the session ends because the system administration
        // switched off the local login, the login page would otherwise keep showing the form
        // from application start. Not awaited - the end of session applies at once.
        void get().refreshPublicAuthConfig()
      }
    },

    requirePasswordChange: (reason) => {
      set({ passwordChangeRequired: true, passwordChangeReason: reason })
    },
  }
}
