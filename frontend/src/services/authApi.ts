import axios from 'axios'
import type { AuthConfig, AuthUser } from '../types/auth'
import { DEV_USER_HEADER, getDevUser } from './devAuth'

const authClient = axios.create({ baseURL: '/api' })

export async function getAuthConfig(): Promise<AuthConfig> {
  const { data } = await authClient.get<AuthConfig>('/v1/auth/config')
  return data
}

/**
 * Fetches the current user. `token` is null in dev mode, where the backend derives the identity
 * from the selected dev user rather than from a bearer token.
 */
export async function getMe(token: string | null): Promise<AuthUser> {
  const headers: Record<string, string> = {}
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  const devUser = getDevUser()
  if (devUser) {
    headers[DEV_USER_HEADER] = devUser
  }

  const { data } = await authClient.get<AuthUser>('/v1/auth/me', { headers })
  return data
}
