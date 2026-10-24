import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { AxiosError, AxiosHeaders } from 'axios'
import { User, UserManager } from 'oidc-client-ts'
import { server } from '@/test/server'
import { mockLocalAccount } from '../mocks/authFixtures'
import { useAuthStore } from './authStore'
import { FieldValidationError } from './authApi'
import {
  RECENT_LOGIN_NOT_CONFIRMED_BY_PROVIDER_MESSAGE,
  RECENT_LOGIN_REQUIRED_MESSAGE,
  consumeRecentLoginReturn,
  isRecentLoginRequired,
  recentLoginRefusalMessage,
} from './recentLogin'

const provider = {
  id: 'p-opaa',
  displayName: 'Verzeichnisdienst',
  issuerUri: 'https://idp.example.test/realms/opaa',
  clientId: 'opaa-frontend',
  isDefault: true,
  sortOrder: 0,
}

function refusal(code: string): AxiosError {
  const config = { headers: new AxiosHeaders() }
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', config, null, {
    status: 403,
    statusText: 'Forbidden',
    headers: {},
    config,
    data: { error: 'x', status: 403, timestamp: '', code, reason: 'AUTH_TIME_TOO_OLD' },
  })
}

describe('recent sign-in', () => {
  beforeEach(() => {
    sessionStorage.clear()
    localStorage.clear()
    consumeRecentLoginReturn()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('recognises the refusal of an operation that requires a recent sign-in', () => {
    expect(isRecentLoginRequired(refusal('RECENT_LOGIN_REQUIRED'))).toBe(true)
    expect(isRecentLoginRequired(refusal('CAPABILITY_REQUIRED'))).toBe(false)
    expect(recentLoginRefusalMessage(false)).toBe(RECENT_LOGIN_REQUIRED_MESSAGE)
    expect(recentLoginRefusalMessage(true)).toBe(RECENT_LOGIN_NOT_CONFIRMED_BY_PROVIDER_MESSAGE)
  })

  it('adopts the token of a password confirmation of the local session', async () => {
    useAuthStore.setState({ mode: 'oidc', sessionKind: 'local', token: 'old-token' })

    await useAuthStore.getState().confirmSignInWithPassword(mockLocalAccount.password)

    expect(useAuthStore.getState().token).toBe('mock-recent-login-token')
  })

  it('keeps the token and rejects with the field error when the password is wrong', async () => {
    useAuthStore.setState({ mode: 'oidc', sessionKind: 'local', token: 'old-token' })

    await expect(
      useAuthStore.getState().confirmSignInWithPassword('falsch'),
    ).rejects.toBeInstanceOf(FieldValidationError)
    expect(useAuthStore.getState().token).toBe('old-token')
  })

  describe('at the provider', () => {
    beforeEach(async () => {
      server.use(
        http.get('/api/v1/auth/config', () =>
          HttpResponse.json({ mode: 'oidc', providers: [provider] }),
        ),
      )
      await useAuthStore.getState().initialize()
    })

    it('forces a sign-in with prompt=login and max_age=0 and comes back to the page', async () => {
      const redirect = vi
        .spyOn(UserManager.prototype, 'signinRedirect')
        .mockResolvedValue(undefined)

      await useAuthStore.getState().confirmSignInAtProvider('/settings/daten')

      expect(redirect).toHaveBeenCalledWith({
        prompt: 'login',
        max_age: 0,
        state: { returnTo: '/settings/daten', recentLogin: true },
      })
    })

    it('marks the return from the forced sign-in once, and only for that flow', async () => {
      sessionStorage.setItem('opaa.oidc.flowProvider', 'p-opaa')
      const user = (state: unknown) =>
        new User({
          access_token: 'fresh-token',
          token_type: 'Bearer',
          profile: {
            sub: 'user-9',
            iss: provider.issuerUri,
            aud: provider.clientId,
            exp: Math.floor(Date.now() / 1000) + 900,
            iat: Math.floor(Date.now() / 1000),
          },
          expires_at: Math.floor(Date.now() / 1000) + 900,
          userState: state,
        })
      const callback = vi.spyOn(UserManager.prototype, 'signinRedirectCallback')

      callback.mockResolvedValueOnce(user({ returnTo: '/settings/daten', recentLogin: true }))
      expect(await useAuthStore.getState().handleOidcCallback()).toEqual({
        kind: 'session',
        returnTo: '/settings/daten',
      })
      expect(consumeRecentLoginReturn()).toBe(true)
      expect(consumeRecentLoginReturn()).toBe(false)

      sessionStorage.setItem('opaa.oidc.flowProvider', 'p-opaa')
      callback.mockResolvedValueOnce(user({ returnTo: '/chat' }))
      await useAuthStore.getState().handleOidcCallback()
      expect(consumeRecentLoginReturn()).toBe(false)
    })
  })
})
