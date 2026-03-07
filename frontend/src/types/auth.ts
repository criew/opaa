export type AuthMode = 'mock' | 'oidc' | 'basic'

export interface AuthConfig {
  mode: AuthMode
  authority?: string
  clientId?: string
}

export type SystemRole = 'USER' | 'SYSTEM_ADMIN'

export interface AuthUser {
  id: string
  email: string | null
  displayName: string | null
  systemRole: SystemRole
}

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  accessToken: string
  expiresIn: number
}
