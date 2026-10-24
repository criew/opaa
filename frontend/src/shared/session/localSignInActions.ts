import {
  changePassword as changePasswordRequest,
  describeLocalSignInFailure,
  endsSession,
  loginLocal as loginLocalRequest,
} from './authApi'
import {
  LOCAL_SIGN_IN_FAILED_MESSAGE,
  LOCAL_SIGN_IN_INCOMPLETE_MESSAGE,
  LOCAL_SIGN_IN_UNREACHABLE_MESSAGE,
  tooManyAttemptsMessage,
} from './authMessages'
import { spendSilentSignIn } from './silentSignIn'
import { sessionSetupFailureMessage } from './localSession'
import type { AuthState, AuthStoreContext } from './authState'

export function createLocalSignInActions(
  ctx: AuthStoreContext,
): Pick<AuthState, 'loginLocal' | 'changePassword'> {
  const { set, get, local } = ctx
  return {
    loginLocal: async (email, password) => {
      spendSilentSignIn()
      set({ isSigningIn: true, error: null })
      let tokens
      try {
        tokens = await loginLocalRequest(email, password)
      } catch (err) {
        local.dropLocalTokens()
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
        await local.adoptLocalSession(tokens)
        return true
      } catch (err) {
        // The credentials were right - the identity call was not. Saying "check e-mail address and
        // password" here would send the person after a mistake they did not make, and a backend
        // that was briefly unreachable must not cost another tab its session note either.
        if (endsSession(err)) local.endLocalSession()
        else local.dropLocalTokens()
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
      await local.adoptLocalSession(tokens)
    },
  }
}
