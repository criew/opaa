import { getAuthConfig, getMe } from './authApi'
import { resolveDevUser } from './devAuth'
import { AUTH_CALLBACK_ROUTE } from '../routes'
import {
  CONFIG_UNAVAILABLE_MESSAGE,
  NO_PROVIDER_MESSAGE,
  SESSION_EXPIRED_MESSAGE,
} from './authMessages'
import { clearHandoverInFlight, isHandoverInFlight } from './handoverFlow'
import { sessionSetupFailureMessage } from './localSession'
import { readStorage, writeStorage } from '../utils/storage'
import { FLOW_PROVIDER_STORAGE_KEY, LAST_PROVIDER_STORAGE_KEY } from './authState'
import type { AuthState, AuthStoreContext } from './authState'

export function createAuthConfigActions(
  ctx: AuthStoreContext,
): Pick<AuthState, 'initialize' | 'suggestedProvider' | 'refreshPublicAuthConfig'> {
  const { set, get, oidc, local } = ctx
  return {
    initialize: async () => {
      let config
      try {
        config = await getAuthConfig()
      } catch {
        // Deliberately no fallback to an authenticated-looking state: after a failing
        // /api/v1/auth/config the backend rejects every request, so a signed-in-looking UI would
        // be entirely non-functional. Surfacing the failure is the honest outcome.
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
      const { localAccounts, features, installationPending } = config
      set({ mode: config.mode, providers, localAccounts, features, installationPending })

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

      oidc.installUserManagers(providers)
      // a suggestion for a provider that no longer exists would propose a dead end
      const last = readStorage(localStorage, LAST_PROVIDER_STORAGE_KEY)
      if (last && !oidc.hasUserManager(last)) {
        writeStorage(localStorage, LAST_PROVIDER_STORAGE_KEY, null)
      }
      // the session (or the flow under way) belongs to the provider this tab remembers; a
      // provider disabled in the meantime is simply no longer there, and the tab starts over
      const remembered = readStorage(sessionStorage, FLOW_PROVIDER_STORAGE_KEY)
      const active =
        remembered && oidc.hasUserManager(remembered) ? oidc.activate(remembered, true) : null
      if (!active && remembered) {
        writeStorage(sessionStorage, FLOW_PROVIDER_STORAGE_KEY, null)
      }
      if (!active) {
        // no session and no flow under way: the suggested provider's manager stands ready,
        // so token plumbing (renew, expiry) has a manager to talk to from the first moment
        const suggested = get().suggestedProvider()
        if (suggested) oidc.activate(suggested.id, false)
      }
      // A handover comes back through this callback, and on that trip the local session
      // must stay untouched - it is about to be revoked, and ADR-0033 Entscheidung 12 allows no
      // authenticated call between the callback and the redemption, the local refresh and
      // /auth/me of the restore included. The note lives only for that trip: any other route
      // clears it - read before the branches below, so no early return walks past the cleanup -
      // and an abandoned flow therefore cannot suppress the restore of the next session.
      const handoverCallback =
        isHandoverInFlight() && window.location.pathname === AUTH_CALLBACK_ROUTE
      if (!handoverCallback) clearHandoverInFlight()
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
          oidc.dropLocalSession()
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
      if (!handoverCallback && (await local.restoreLocalSession()) !== 'none') return
      // The configuration this call just loaded decides, not the store state after the await: the
      // restore attempt above can end a session (in this tab or another) and reset the store's
      // block to switched-off in the meantime, which must not overrule a config that says enabled.
      if (providers.length === 0 && !localAccounts.enabled) {
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

    /**
     * The public login configuration is outdated after every end of session: during
     * a session the system administration may have toggled the local login, „Passwort vergessen",
     * the self-registration or an identity provider. Without this reload
     * the login page would show the state as of application start until someone reloaded the page.
     *
     * Deliberately **not** `initialize()`: that signs in again at once in the `dev` operating mode
     * and would undo the session that just ended.
     */
    refreshPublicAuthConfig: async () => {
      try {
        const config = await getAuthConfig()
        set({
          mode: config.mode,
          providers: config.providers ?? [],
          localAccounts: config.localAccounts,
          features: config.features,
          installationPending: config.installationPending,
        })
      } catch {
        // The last known state is better than none: an unreachable configuration
        // must not clear the login page, and the logout applies regardless.
      }
    },
  }
}
