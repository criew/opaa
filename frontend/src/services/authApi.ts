import axios from 'axios'
import type { AuthConfig, AuthUser, LoginRequest, LoginResponse } from '../types/auth'

const authClient = axios.create({ baseURL: '/api' })

export async function getAuthConfig(): Promise<AuthConfig> {
  const { data } = await authClient.get<AuthConfig>('/v1/auth/config')
  return data
}

export async function login(request: LoginRequest): Promise<LoginResponse> {
  const { data } = await authClient.post<LoginResponse>('/v1/auth/login', request)
  return data
}

export async function getMe(token: string): Promise<AuthUser> {
  const { data } = await authClient.get<AuthUser>('/v1/auth/me', {
    headers: { Authorization: `Bearer ${token}` },
  })
  return data
}
