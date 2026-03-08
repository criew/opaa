import type { components } from './generated/api'

export type AuthMode = 'mock' | 'oidc' | 'basic'

export type AuthConfigResponse = components['schemas']['AuthConfigResponse']

export interface AuthConfig {
  mode: AuthMode
  authority?: string
  clientId?: string
}

export type SystemRole = components['schemas']['SystemRole']

export interface AuthUser {
  id: string
  email: string | null
  displayName: string | null
  systemRole: SystemRole
}

export type LoginRequest = components['schemas']['LoginRequest']
export type LoginResponse = components['schemas']['LoginResponse']
export type UserInfoResponse = components['schemas']['UserInfoResponse']
export type RoleChangeRequest = components['schemas']['RoleChangeRequest']
