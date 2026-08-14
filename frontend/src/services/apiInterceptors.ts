import type { AxiosInstance } from 'axios'
import { DEV_USER_HEADER, getDevUser } from './devAuth'

type TokenGetter = () => string | null
type LogoutFn = () => void

export function setupAuthInterceptors(
  client: AxiosInstance,
  getToken: TokenGetter,
  onUnauthorized: LogoutFn,
) {
  client.interceptors.request.use((config) => {
    const token = getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    // Only ever set in dev mode; the backend ignores it under oidc.
    const devUser = getDevUser()
    if (devUser) {
      config.headers[DEV_USER_HEADER] = devUser
    }
    return config
  })

  client.interceptors.response.use(
    (response) => response,
    (error) => {
      if (error.response?.status === 401) {
        onUnauthorized()
      }
      return Promise.reject(error)
    },
  )
}
