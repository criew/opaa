import type { AxiosInstance } from 'axios'

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
