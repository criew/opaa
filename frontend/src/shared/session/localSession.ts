import type { LocalTokenResponse } from './types'
import {
  endsSession,
  forgetLocalSession,
  getMe,
  performLocalRefresh,
  rememberLocalSession,
  UnknownIssuerError,
} from './authApi'
import { sessionEndingReason } from '../api/apiInterceptors'
import {
  SESSION_EXPIRED_MESSAGE,
  SESSION_UNAVAILABLE_MESSAGE,
  UNKNOWN_ISSUER_MESSAGE,
  sessionEndMessage,
} from './authMessages'
import type { AuthSet } from './authState'

// When the access token of the local session stops being valid (epoch ms). Plumbing for logout,
// which may only present a bearer the resource server still accepts; a UI never reads it.
let localTokenExpiresAt: number | null = null

/**
 * Why a session that looked established could not be taken up: the backend names the cause in the
 * challenge of its refusal (ADR-0033, Entscheidung 8) - a locked account or a revoked session is
 * not the same as a token that merely expired, and the person is told which it was.
 */
export function sessionSetupFailureMessage(err: unknown, fallback: string): string {
  if (err instanceof UnknownIssuerError) return UNKNOWN_ISSUER_MESSAGE
  const reason = sessionEndingReason(err)
  return reason ? sessionEndMessage(reason) : fallback
}

export function createLocalSession(set: AuthSet) {
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

  /** Whether the access token of the local session is still accepted by the resource server. */
  function isLocalTokenStillValid(): boolean {
    return localTokenExpiresAt !== null && Date.now() < localTokenExpiresAt
  }

  function recordLocalTokenExpiry(expiresInSeconds: number) {
    localTokenExpiresAt = Date.now() + expiresInSeconds * 1000
  }

  return {
    adoptLocalSession,
    dropLocalTokens,
    endLocalSession,
    restoreLocalSession,
    isLocalTokenStillValid,
    recordLocalTokenExpiry,
  }
}

export type LocalSession = ReturnType<typeof createLocalSession>
