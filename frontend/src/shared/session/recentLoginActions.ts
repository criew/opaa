import { reauthenticateLocal } from './authApi'
import { PROVIDER_GONE_MESSAGE } from './authMessages'
import type { AuthState, AuthStoreContext, SignInState } from './authState'

export function createRecentLoginActions(
  ctx: AuthStoreContext,
): Pick<AuthState, 'confirmSignInWithPassword' | 'confirmSignInAtProvider'> {
  const { set, get, oidc, local } = ctx
  return {
    confirmSignInWithPassword: async (password) => {
      const tokens = await reauthenticateLocal(get().token, password)
      local.recordLocalTokenExpiry(tokens.expiresInSeconds)
      set({ token: tokens.accessToken })
    },

    confirmSignInAtProvider: async (returnTo) => {
      const providerId = get().activeProviderId
      const userManager = providerId ? oidc.beginSignInFlow(providerId) : null
      if (!userManager) throw new Error(PROVIDER_GONE_MESSAGE)
      // `max_age=0` and `prompt=login` both demand a sign-in now, whatever session the provider
      // still holds; which of the two a provider honours varies.
      await userManager.signinRedirect({
        prompt: 'login',
        max_age: 0,
        state: { returnTo, recentLogin: true } satisfies SignInState,
      })
    },
  }
}
