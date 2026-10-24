import type { StoreApi } from 'zustand'
import type { UserManager } from 'oidc-client-ts'
import type {
  AuthMode,
  AuthUser,
  FeatureFlags,
  LocalAccountsConfig,
  PasswordChangeReason,
  SessionKind,
  SignInProvider,
} from './types'
import type { SessionExpiredReason } from '../api/apiInterceptors'
import type { LocalSession } from './localSession'
import type { OidcSession } from './oidcSession'

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

/**
 * What a sign-in carries through the provider redirect. oidc-client-ts keeps it with the sign-in
 * state of exactly this flow (this tab's sessionStorage) and hands it back from
 * `signinRedirectCallback()`; it is still read as untrusted input.
 */
export interface SignInState {
  handoverCode?: unknown
  returnTo?: unknown
  /** The sign-in was forced to confirm a recent one (see `recentLogin.ts`). */
  recentLogin?: unknown
}

/**
 * What a completed provider callback was. `returnTo` is always a same-origin path - the route the
 * sign-in was started for, or the chat page. A refusal carries it too: the sign-in the person is
 * now asked for by hand has to lead to the same place the link named.
 */
export type OidcCallbackOutcome =
  | { kind: 'session'; returnTo: string }
  | { kind: 'handover' }
  /** The provider turned the automatic attempt down - no failure of anybody's. */
  | { kind: 'silent-refused'; returnTo: string }
  | { kind: 'failed' }

export interface AuthState {
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
  /**
   * The installation-wide feature flags (ADR-0044) from the public configuration, known before
   * and after sign-in. A flag that is absent counts as on; read them through {@link
   * isFeatureEnabled} or `useFeatureEnabled`, never by indexing this object.
   */
  features: FeatureFlags
  /** The kind of the active session, or of the sign-in under way; null while signed out. */
  sessionKind: SessionKind | null
  /** The account must set a new password before any route but /account/password answers. */
  passwordChangeRequired: boolean
  /** Why the change is demanded - the sentence the password page shows. */
  passwordChangeReason: PasswordChangeReason | null
  /**
   * The person signed out on purpose. The route left behind is then no return target: the next
   * sign-in in this tab may be somebody else. A session that ended on its own clears it again.
   */
  signedOut: boolean
  /**
   * A sign-out is on its way to the provider's end-session endpoint. oidc-client-ts removes the
   * user before the browser leaves, so without this the login page would show - and be usable -
   * in between. Stays set once the redirect started: the page is leaving anyway.
   */
  isSigningOut: boolean

  initialize: () => Promise<void>
  /**
   * Re-reads what `GET /api/v1/auth/config` says about the sign-in - operating mode, providers and
   * the local-accounts block - and touches nothing about the session. Unlike {@link initialize} it
   * therefore never signs anybody in.
   */
  refreshPublicAuthConfig: () => Promise<void>
  /**
   * Starts the sign-in at `providerId` (default: the suggested provider). `silent` sends
   * `prompt=none` and belongs to {@link AuthState.attemptSilentSignIn} alone; every other sign-in
   * leaves the prompt to the provider, which asks for credentials only where it has no session.
   * `returnTo` travels with the flow and comes back from {@link handleOidcCallback}.
   */
  loginOidc: (
    providerId?: string,
    options?: { silent?: boolean; returnTo?: string },
  ) => Promise<void>
  /**
   * Takes up a running provider session without a click: one authorization redirect with
   * `prompt=none` to the suggested provider, answered either with a session or with a refusal that
   * brings the person back to the sign-in page. `returnTo` travels with it like it does with a
   * clicked sign-in. Answers whether the attempt was made - it is not, whenever the sign-in page is
   * what the person is owed: no provider to try, a session already, an attempt already spent in
   * this tab, or a message on the page that the redirect would carry away unread.
   */
  attemptSilentSignIn: (returnTo?: string) => Promise<boolean>
  /**
   * Whether that attempt is still to come in this very moment. Read while rendering, like
   * {@link AuthState.suggestedProvider}: the sign-in page shows itself as busy until the question
   * is settled, because its tiles would otherwise be there to click for the instant between the
   * configuration arriving and the redirect leaving.
   */
  isSilentSignInPending: () => boolean
  /**
   * Completes the provider redirect. Answers what the callback was: an ordinary session, a handover
   * whose provider token is now waiting in {@link ./handoverFlow} - deliberately **without** any
   * authenticated call, `/auth/me` included (ADR-0033, Entscheidung 12) - or a failure whose reason
   * is in {@link AuthState.error}.
   */
  handleOidcCallback: () => Promise<OidcCallbackOutcome>
  /**
   * Starts the provider sign-in of a handover: the code rides through the redirect in
   * oidc-client-ts's own sign-in state, so the page that comes back has it without any storage of
   * our own. The provider is the one the handover names - never one the person picks.
   */
  startHandoverSignIn: (providerId: string, code: string) => Promise<boolean>
  /**
   * Takes up the provider session right after a redeemed handover: the local session of this
   * browser is over for good, and the account now answers under the provider identity.
   */
  completeHandover: (providerToken: string) => Promise<void>
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
  /**
   * Confirms the sign-in of the local session with its password for an operation that requires a
   * recent one, and adopts the access token the backend mints for it. A wrong password rejects with
   * a `FieldValidationError` and counts as a failed attempt.
   */
  confirmSignInWithPassword: (password: string) => Promise<void>
  /**
   * Confirms the sign-in of the provider session by a sign-in forced at the provider (`prompt=login`,
   * `max_age=0`). The page leaves; the callback returns to `returnTo` and marks the return for
   * `consumeRecentLoginReturn`. Rejects when the provider of the session is gone.
   */
  confirmSignInAtProvider: (returnTo: string) => Promise<void>
  logout: () => Promise<void>
  getAccessToken: () => Promise<string | null>
  // A single silent-renew attempt via the refresh token, used by the response interceptor
  // (apiInterceptors.ts) on a 401 before it retries the original request. Returns whether the
  // renew succeeded - no iframe involved, see createUserManager in oidcSession.ts.
  renewToken: () => Promise<boolean>
  // The "second failure" branch of the 401 handling - resets local session state without
  // signoutRedirect(), which would also destroy the IdP session. Left for the OIDC case; a
  // deliberate logout button click still calls logout() above and its full signoutRedirect().
  expireSession: (reason?: SessionExpiredReason) => void
  /** The 403 of the forced password change (ADR-0033, Entscheidung 8), from the interceptor. */
  requirePasswordChange: (reason: PasswordChangeReason | null) => void
  /** The provider the sign-in page proposes: the one used last, else the default, else the first. */
  suggestedProvider: () => SignInProvider | null
}

export type AuthSet = StoreApi<AuthState>['setState']
export type AuthGet = StoreApi<AuthState>['getState']

/** What every action group of the store works with: its setter and getter and the session plumbing. */
export interface AuthStoreContext {
  set: AuthSet
  get: AuthGet
  oidc: OidcSession
  local: LocalSession
}
