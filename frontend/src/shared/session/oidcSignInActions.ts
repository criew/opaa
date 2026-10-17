import type { SigninRedirectArgs } from 'oidc-client-ts'
import { getMe, UnknownIssuerError } from './authApi'
import { PROVIDER_GONE_MESSAGE, UNKNOWN_ISSUER_MESSAGE, signInFailedMessage } from './authMessages'
import { clearHandoverInFlight, markHandoverInFlight, setPendingHandover } from './handoverFlow'
import { safeRedirectPath } from './safeRedirectPath'
import {
  authorizationErrorCode,
  clearSilentSignInFlow,
  isAuthorizationRefusal,
  isSilentSignInFlow,
  isSilentSignInSpent,
  spendSilentSignIn,
} from './silentSignIn'
import { resetAllStores } from './resettableStores'
import { readStorage, writeStorage } from '../utils/storage'
import { FLOW_PROVIDER_STORAGE_KEY, LAST_PROVIDER_STORAGE_KEY } from './authState'
import type { AuthState, AuthStoreContext, SignInState } from './authState'

export function createOidcSignInActions(
  ctx: AuthStoreContext,
): Pick<
  AuthState,
  | 'loginOidc'
  | 'isSilentSignInPending'
  | 'attemptSilentSignIn'
  | 'handleOidcCallback'
  | 'startHandoverSignIn'
  | 'completeHandover'
> {
  const { set, get, oidc, local } = ctx
  return {
    loginOidc: async (providerId, options) => {
      const silent = options?.silent === true
      // A sign-in is under way in this tab, so the one automatic attempt is spent - see
      // spendSilentSignIn() for why that holds for a click just as much as for the attempt itself.
      spendSilentSignIn()
      const chosen = providerId ?? get().suggestedProvider()?.id
      const provider = get().providers.find((p) => p.id === chosen)
      const userManager = chosen ? oidc.beginSignInFlow(chosen, { silent }) : null
      if (!chosen || !provider || !userManager) {
        // No flow got under way, but the attempt above is spent - say so, or the sign-in page
        // keeps showing itself as busy for a redirect that is not coming.
        set({ isSigningIn: false, ...(silent ? {} : { error: PROVIDER_GONE_MESSAGE }) })
        return
      }
      // The suggestion names the provider a person chose last; an attempt that may well be refused
      // is not a choice, and claiming that place would also mislabel a tile as "Zuletzt verwendet".
      if (!silent) writeStorage(localStorage, LAST_PROVIDER_STORAGE_KEY, chosen)
      set({ isSigningIn: true, sessionKind: 'oidc', error: null })
      try {
        const args: SigninRedirectArgs = {}
        if (silent) {
          // `prompt=none` forbids the provider a screen of its own - it answers from a
          // running session or not at all. `replace` keeps the authorization URL out of the
          // history, so "back" from the sign-in page it returns to cannot land on it.
          args.prompt = 'none'
          args.redirectMethod = 'replace'
        }
        if (options?.returnTo) args.state = { returnTo: options.returnTo } satisfies SignInState
        await userManager.signinRedirect(args)
      } catch (err) {
        // discovery unreachable (CSP not yet widened, DNS, provider down): say so instead of a
        // click that visibly does nothing - unless nobody clicked, see silentSignIn.ts
        writeStorage(sessionStorage, FLOW_PROVIDER_STORAGE_KEY, null)
        clearSilentSignInFlow()
        set({
          isSigningIn: false,
          error: silent
            ? null
            : signInFailedMessage(
                provider.displayName,
                err instanceof Error ? err.message : String(err),
              ),
        })
      }
    },

    isSilentSignInPending: () => {
      const { mode, providers, isAuthenticated, error } = get()
      if (mode !== 'oidc' || isAuthenticated) return false
      // Nothing to try, or a sentence on the page that the redirect would carry away before anybody
      // read it - a session that ended with a reason (expireSession) is exactly that case.
      if (providers.length === 0 || error !== null) return false
      return !isSilentSignInSpent()
    },

    attemptSilentSignIn: async (returnTo) => {
      // One predicate for both, so what the page shows as busy and what actually happens can never
      // drift apart. The load itself is only this one's business: the page is busy while it runs.
      if (get().isLoading || !get().isSilentSignInPending()) return false
      const suggested = get().suggestedProvider()
      if (!suggested) return false
      await get().loginOidc(suggested.id, { silent: true, returnTo })
      return true
    },

    handleOidcCallback: async () => {
      const { userManager, activeProviderId } = get()
      // the flow's provider is pinned per tab; without it (or with a provider disabled in the
      // meantime, whose manager was never built) there is nothing to complete the callback with
      const flowProvider = readStorage(sessionStorage, FLOW_PROVIDER_STORAGE_KEY)
      // Whether the redirect coming back was the automatic attempt. Read and dropped before
      // any branch below returns, so a callback that lands here twice is judged once; that the note
      // belongs to this flow and no earlier one is settled where it is written (see loginOidc).
      const silentFlow = isSilentSignInFlow()
      clearSilentSignInFlow()
      if (!userManager || !flowProvider || flowProvider !== activeProviderId) {
        clearHandoverInFlight()
        if (silentFlow) {
          // The provider was disabled while an attempt nobody asked for was under way: still not
          // this person's failure, and the sign-in page lists what is left. Without a manager there
          // is no sign-in state to read a route from, so the sign-in page starts over at its own.
          set({ error: null, isLoading: false, isSigningIn: false })
          return { kind: 'silent-refused', returnTo: safeRedirectPath(null) }
        }
        set({ error: PROVIDER_GONE_MESSAGE, isLoading: false })
        return { kind: 'failed' }
      }
      try {
        const oidcUser = await userManager.signinRedirectCallback()
        const signInState = oidcUser.state as SignInState | undefined
        const handoverCode =
          typeof signInState?.handoverCode === 'string' ? signInState.handoverCode : null
        if (!handoverCode) clearHandoverInFlight()
        if (handoverCode) {
          // ADR-0033, Entscheidung 12: not one authenticated request between the callback and the
          // redemption - the account this token names must not exist yet, and /auth/me would
          // create it.
          setPendingHandover({ code: handoverCode, providerToken: oidcUser.access_token })
          set({ isLoading: false })
          return { kind: 'handover' }
        }
        const me = await getMe(oidcUser.access_token)
        set({
          token: oidcUser.access_token,
          user: me,
          isAuthenticated: true,
          isLoading: false,
          sessionKind: 'oidc',
        })
        const returnTo = typeof signInState?.returnTo === 'string' ? signInState.returnTo : null
        return { kind: 'session', returnTo: safeRedirectPath(returnTo) }
      } catch (err) {
        clearHandoverInFlight()
        if (err instanceof UnknownIssuerError) {
          oidc.dropLocalSession()
          set({ error: UNKNOWN_ISSUER_MESSAGE, isLoading: false })
          return { kind: 'failed' }
        }
        if (silentFlow && isAuthorizationRefusal(err)) {
          // No session at the provider (or none it will hand over unasked): the person asked for
          // none of this, so the sign-in page comes back as it was, without an error of its own.
          // Logged all the same - an installation whose prompt=none is refused for a reason other
          // than a missing session (a client the provider does not know, a redirect URI it does not
          // accept) would have nothing at all to go by otherwise. Never the error object itself:
          // oidc-client-ts's ErrorResponse carries the failed token request in `form`.
          console.warn('Silent sign-in refused by the provider', authorizationErrorCode(err))
          set({ error: null, isLoading: false, isSigningIn: false })
          // The route the link named is in the sign-in state of this very flow, and
          // oidc-client-ts hands that state out with the refusal too (ErrorResponse.state). Without
          // it the sign-in the person is now asked for by hand would end on the chat page, and the
          // automatic attempt would have broken the direct link exactly where it could not help.
          // Still read as untrusted input, like the state of a completed callback above.
          const refusedState = (err as { state?: unknown }).state as SignInState | undefined
          const refusedReturnTo =
            typeof refusedState?.returnTo === 'string' ? refusedState.returnTo : null
          return { kind: 'silent-refused', returnTo: safeRedirectPath(refusedReturnTo) }
        }
        set({
          error: err instanceof Error ? err.message : 'OIDC-Rückmeldung fehlgeschlagen',
          isLoading: false,
        })
        return { kind: 'failed' }
      }
    },

    startHandoverSignIn: async (providerId, code) => {
      spendSilentSignIn()
      const provider = get().providers.find((p) => p.id === providerId)
      const userManager = oidc.beginSignInFlow(providerId)
      if (!provider || !userManager) {
        set({ error: PROVIDER_GONE_MESSAGE })
        return false
      }
      writeStorage(localStorage, LAST_PROVIDER_STORAGE_KEY, providerId)
      set({ isSigningIn: true, error: null })
      markHandoverInFlight()
      try {
        await userManager.signinRedirect({ state: { handoverCode: code } satisfies SignInState })
        return true
      } catch (err) {
        clearHandoverInFlight()
        writeStorage(sessionStorage, FLOW_PROVIDER_STORAGE_KEY, null)
        set({
          isSigningIn: false,
          error: signInFailedMessage(
            provider.displayName,
            err instanceof Error ? err.message : String(err),
          ),
        })
        return false
      }
    },

    completeHandover: async (providerToken) => {
      resetAllStores()
      local.endLocalSession()
      const me = await getMe(providerToken)
      set({
        token: providerToken,
        user: me,
        isAuthenticated: true,
        isLoading: false,
        isSigningIn: false,
        sessionKind: 'oidc',
        passwordChangeRequired: false,
        passwordChangeReason: null,
        error: null,
      })
    },
  }
}
