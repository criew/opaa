import type { components } from './generated/api'

export type AuthMode = 'oidc' | 'dev'

export type AuthConfigResponse = components['schemas']['AuthConfigResponse']

export type SignInProvider = components['schemas']['OidcSignInProvider']

export interface AuthConfig {
  mode: AuthMode
  /** The enabled providers a sign-in can start at, in sign-in page order; empty in dev mode. */
  providers: SignInProvider[]
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
