import type { components } from './generated/api'

export type AuthMode = 'oidc' | 'dev'

export type AuthConfigResponse = components['schemas']['AuthConfigResponse']

export type SignInProvider = components['schemas']['OidcSignInProvider']

export type LocalAccountsConfig = components['schemas']['LocalAccountsConfig']

export type LocalTokenResponse = components['schemas']['LocalTokenResponse']

export type PasswordChangeReason = components['schemas']['PasswordChangeReason']

export type FieldError = components['schemas']['FieldError']

/**
 * Which kind of session a tab holds (ADR-0033): a provider session managed by oidc-client-ts, or
 * a local one whose access token lives in memory only and is renewed through the HttpOnly refresh
 * cookie. Never both at once.
 */
export type SessionKind = 'oidc' | 'local'

/**
 * What local accounts offer right now. Absent from an older backend's response, in which case the
 * management counts as switched off.
 */
export const LOCAL_ACCOUNTS_DISABLED: LocalAccountsConfig = {
  enabled: false,
  selfRegistrationEnabled: false,
  passwordResetEnabled: false,
  passwordMinLength: 12,
}

export interface AuthConfig {
  mode: AuthMode
  /** The enabled providers a sign-in can start at, in sign-in page order; empty in dev mode. */
  providers: SignInProvider[]
  /** The LOCAL provider row, which never appears among {@link providers} (ADR-0033). */
  localAccounts?: LocalAccountsConfig
}

export type SystemRole = components['schemas']['SystemRole']

export interface AuthUser {
  id: string
  email: string | null
  displayName: string | null
  systemRole: SystemRole
}

export type UserInfoResponse = components['schemas']['UserInfoResponse']
export type RoleChangeRequest = components['schemas']['RoleChangeRequest']
