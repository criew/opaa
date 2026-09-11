import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import { useAuthStore } from './authStore'
import { useSpaceStore } from './spaceStore'
import { LOCAL_ACCOUNTS_DISABLED } from '../types/auth'

const LOCAL_CONFIG = {
  mode: 'oidc',
  providers: [],
  localAccounts: {
    enabled: true,
    selfRegistrationEnabled: false,
    passwordResetEnabled: false,
    passwordMinLength: 12,
  },
}

const TOKEN = {
  accessToken: 'local-access-token',
  expiresInSeconds: 900,
  passwordChangeRequired: false,
}

const ME = {
  id: 'u-1',
  email: 'erika.muster@stadt.example',
  displayName: 'Erika Muster',
  systemRole: 'USER',
}

function withLocalConfig() {
  server.use(http.get('/api/v1/auth/config', () => HttpResponse.json(LOCAL_CONFIG)))
}

function setCsrfCookie() {
  document.cookie = 'XSRF-TOKEN=csrf-1; path=/'
}

function clearCookies() {
  for (const entry of document.cookie.split(';')) {
    const name = entry.split('=')[0]?.trim()
    if (name) document.cookie = `${name}=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT`
  }
}

/**
 * The local session of ADR-0033 in the store: restored from the refresh cookie, renewed through a
 * single shared request, ended with the CSRF double-submit header.
 */
describe('authStore - local session', () => {
  beforeEach(() => {
    clearCookies()
    sessionStorage.clear()
    localStorage.clear()
    useAuthStore.setState({
      mode: null,
      user: null,
      token: null,
      isAuthenticated: false,
      isLoading: true,
      isSigningIn: false,
      error: null,
      providers: [],
      userManager: null,
      activeProviderId: null,
      localAccounts: LOCAL_ACCOUNTS_DISABLED,
      sessionKind: null,
      passwordChangeRequired: false,
      passwordChangeReason: null,
    })
  })

  afterEach(() => {
    clearCookies()
  })

  it('makes no refresh call at all while there is no CSRF cookie', async () => {
    withLocalConfig()
    const refreshes = vi.fn()
    server.use(
      http.post('/api/v1/auth/local/refresh', () => {
        refreshes()
        return HttpResponse.json(TOKEN)
      }),
    )

    await useAuthStore.getState().initialize()

    expect(refreshes).not.toHaveBeenCalled()
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
    expect(useAuthStore.getState().error).toBeNull()
  })

  it('restores the session from the refresh cookie on a reload', async () => {
    withLocalConfig()
    setCsrfCookie()
    let sentHeader: string | null = null
    server.use(
      http.post('/api/v1/auth/local/refresh', ({ request }) => {
        sentHeader = request.headers.get('X-XSRF-TOKEN')
        return HttpResponse.json(TOKEN)
      }),
      http.get('/api/v1/auth/me', () => HttpResponse.json(ME)),
    )

    await useAuthStore.getState().initialize()

    const state = useAuthStore.getState()
    expect(sentHeader).toBe('csrf-1')
    expect(state.isAuthenticated).toBe(true)
    expect(state.sessionKind).toBe('local')
    expect(state.token).toBe('local-access-token')
    expect(state.user).toEqual(ME)
    expect(sessionStorage.getItem('opaa.auth.sessionKind')).toBe('local')
    expect(localStorage.getItem('opaa.auth.sessionKind')).toBeNull()
  })

  it('stays signed out - without a loop - when the refresh is refused', async () => {
    withLocalConfig()
    setCsrfCookie()
    let calls = 0
    server.use(
      http.post('/api/v1/auth/local/refresh', () => {
        calls += 1
        return new HttpResponse(null, { status: 401 })
      }),
    )

    await useAuthStore.getState().initialize()

    expect(calls).toBe(1)
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
    expect(useAuthStore.getState().isLoading).toBe(false)
  })

  it('signs in with e-mail address and password', async () => {
    let body: unknown = null
    server.use(
      http.post('/api/v1/auth/local/login', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(TOKEN)
      }),
      http.get('/api/v1/auth/me', () => HttpResponse.json(ME)),
    )

    const ok = await useAuthStore.getState().loginLocal('Erika.Muster@stadt.example', 'geheim')

    expect(ok).toBe(true)
    expect(body).toEqual({ email: 'Erika.Muster@stadt.example', password: 'geheim' })
    const state = useAuthStore.getState()
    expect(state.sessionKind).toBe('local')
    expect(state.isAuthenticated).toBe(true)
    expect(state.token).toBe('local-access-token')
  })

  it('answers every refused sign-in with the same sentence', async () => {
    server.use(http.post('/api/v1/auth/local/login', () => new HttpResponse(null, { status: 401 })))

    const ok = await useAuthStore.getState().loginLocal('erika.muster@stadt.example', 'falsch')

    expect(ok).toBe(false)
    expect(useAuthStore.getState().error).toBe(
      'Anmeldung nicht möglich. Prüfen Sie E-Mail-Adresse und Passwort.',
    )
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
    expect(useAuthStore.getState().isSigningIn).toBe(false)
  })

  it('names the wait after a rate-limited sign-in', async () => {
    server.use(
      http.post(
        '/api/v1/auth/local/login',
        () => new HttpResponse(null, { status: 429, headers: { 'Retry-After': '90' } }),
      ),
    )

    await useAuthStore.getState().loginLocal('erika.muster@stadt.example', 'geheim')

    expect(useAuthStore.getState().error).toBe(
      'Zu viele Anmeldeversuche. Bitte versuchen Sie es in 2 Minuten erneut.',
    )
  })

  it('reports an unreachable backend separately from a refusal', async () => {
    server.use(http.post('/api/v1/auth/local/login', () => HttpResponse.error()))

    await useAuthStore.getState().loginLocal('erika.muster@stadt.example', 'geheim')

    expect(useAuthStore.getState().error).toMatch(/Verbindung/)
  })

  it('does not fetch the identity while a password change is owed', async () => {
    const meCalls = vi.fn()
    server.use(
      http.post('/api/v1/auth/local/login', () =>
        HttpResponse.json({
          ...TOKEN,
          passwordChangeRequired: true,
          passwordChangeReason: 'INITIAL',
        }),
      ),
      http.get('/api/v1/auth/me', () => {
        meCalls()
        return HttpResponse.json(ME)
      }),
    )

    await useAuthStore.getState().loginLocal('erika.muster@stadt.example', 'geheim')

    const state = useAuthStore.getState()
    expect(meCalls).not.toHaveBeenCalled()
    expect(state.isAuthenticated).toBe(true)
    expect(state.passwordChangeRequired).toBe(true)
    expect(state.passwordChangeReason).toBe('INITIAL')
    expect(state.user).toBeNull()
  })

  it('adopts the session the password change mints and drops the demand', async () => {
    useAuthStore.setState({
      sessionKind: 'local',
      token: 'old-token',
      isAuthenticated: true,
      passwordChangeRequired: true,
      passwordChangeReason: 'INITIAL',
    })
    server.use(
      http.post('/api/v1/auth/local/change-password', () =>
        HttpResponse.json({ ...TOKEN, accessToken: 'fresh-token' }),
      ),
      http.get('/api/v1/auth/me', () => HttpResponse.json(ME)),
    )

    await useAuthStore.getState().changePassword('alt', 'ein-neues-passwort')

    const state = useAuthStore.getState()
    expect(state.token).toBe('fresh-token')
    expect(state.passwordChangeRequired).toBe(false)
    expect(state.user).toEqual(ME)
  })

  // ADR-0033, Entscheidung 7: presenting the same rotating refresh token twice counts as a replay
  // and revokes every session of the account - concurrent callers must share one request.
  it('renews through a single shared request', async () => {
    setCsrfCookie()
    let calls = 0
    server.use(
      http.post('/api/v1/auth/local/refresh', async () => {
        calls += 1
        await new Promise((resolve) => setTimeout(resolve, 10))
        return HttpResponse.json({ ...TOKEN, accessToken: 'renewed-token' })
      }),
    )
    useAuthStore.setState({ sessionKind: 'local', token: 'old', isAuthenticated: true })

    const [first, second] = await Promise.all([
      useAuthStore.getState().renewToken(),
      useAuthStore.getState().renewToken(),
    ])

    expect(first).toBe(true)
    expect(second).toBe(true)
    expect(calls).toBe(1)
    expect(useAuthStore.getState().token).toBe('renewed-token')
  })

  it('reports a refused renewal instead of retrying it', async () => {
    setCsrfCookie()
    server.use(
      http.post('/api/v1/auth/local/refresh', () => new HttpResponse(null, { status: 401 })),
    )
    useAuthStore.setState({ sessionKind: 'local', token: 'old', isAuthenticated: true })

    await expect(useAuthStore.getState().renewToken()).resolves.toBe(false)
  })

  it('repeats a refresh once with a freshly primed CSRF cookie', async () => {
    setCsrfCookie()
    let calls = 0
    server.use(
      http.post('/api/v1/auth/local/refresh', () => {
        calls += 1
        if (calls === 1) {
          return HttpResponse.json(
            {
              error: 'CSRF',
              status: 403,
              timestamp: '2026-09-11T10:00:00Z',
              code: 'CSRF_TOKEN_MISSING',
            },
            { status: 403 },
          )
        }
        return HttpResponse.json(TOKEN)
      }),
    )
    useAuthStore.setState({ sessionKind: 'local', token: 'old', isAuthenticated: true })

    await expect(useAuthStore.getState().renewToken()).resolves.toBe(true)
    expect(calls).toBe(2)
  })

  it('ends the local session at the backend and resets every store', async () => {
    setCsrfCookie()
    let seen: { csrf: string | null; authorization: string | null } | null = null
    server.use(
      http.post('/api/v1/auth/local/logout', ({ request }) => {
        seen = {
          csrf: request.headers.get('X-XSRF-TOKEN'),
          authorization: request.headers.get('Authorization'),
        }
        return new HttpResponse(null, { status: 204 })
      }),
      http.get('/api/v1/auth/me', () => HttpResponse.json(ME)),
      http.post('/api/v1/auth/local/login', () => HttpResponse.json(TOKEN)),
    )
    await useAuthStore.getState().loginLocal('erika.muster@stadt.example', 'geheim')
    useSpaceStore.setState({ spaces: [{ id: 's-1' } as never] })

    await useAuthStore.getState().logout()

    expect(seen).toEqual({ csrf: 'csrf-1', authorization: 'Bearer local-access-token' })
    const state = useAuthStore.getState()
    expect(state.isAuthenticated).toBe(false)
    expect(state.sessionKind).toBeNull()
    expect(state.token).toBeNull()
    expect(sessionStorage.getItem('opaa.auth.sessionKind')).toBeNull()
    expect(useSpaceStore.getState().spaces).toEqual([])
  })

  it('never presents an expired bearer on logout', async () => {
    setCsrfCookie()
    let authorization: string | null = 'unset'
    server.use(
      http.post('/api/v1/auth/local/logout', ({ request }) => {
        authorization = request.headers.get('Authorization')
        return new HttpResponse(null, { status: 204 })
      }),
      http.get('/api/v1/auth/me', () => HttpResponse.json(ME)),
      http.post('/api/v1/auth/local/login', () =>
        HttpResponse.json({ ...TOKEN, expiresInSeconds: 0 }),
      ),
    )
    await useAuthStore.getState().loginLocal('erika.muster@stadt.example', 'geheim')

    await useAuthStore.getState().logout()

    expect(authorization).toBeNull()
  })

  it('signs out locally even when the backend refuses the logout', async () => {
    setCsrfCookie()
    server.use(
      http.post('/api/v1/auth/local/logout', () => new HttpResponse(null, { status: 500 })),
      http.get('/api/v1/auth/me', () => HttpResponse.json(ME)),
      http.post('/api/v1/auth/local/login', () => HttpResponse.json(TOKEN)),
    )
    await useAuthStore.getState().loginLocal('erika.muster@stadt.example', 'geheim')

    await useAuthStore.getState().logout()

    expect(useAuthStore.getState().isAuthenticated).toBe(false)
  })

  it.each([
    ['local_accounts_disabled', /abgeschaltet/],
    ['account_locked:failed_logins', /mehreren Fehlversuchen/],
    ['account_locked:admin', /von der Systemverwaltung gesperrt/],
    ['account_expired', /abgelaufen/],
    ['session_revoked:password_changed', /Passwort geändert/],
    ['account_not_active', /nicht anmeldefähig/],
  ])('ends the session with the named cause for %s', (reason, expected) => {
    useAuthStore.setState({ sessionKind: 'local', isAuthenticated: true, token: 't' })

    useAuthStore.getState().expireSession(reason as never)

    const state = useAuthStore.getState()
    expect(state.isAuthenticated).toBe(false)
    expect(state.sessionKind).toBeNull()
    expect(state.error).toMatch(expected)
  })

  it('reports no missing provider while local accounts are the way in', async () => {
    withLocalConfig()

    await useAuthStore.getState().initialize()

    expect(useAuthStore.getState().error).toBeNull()
    expect(useAuthStore.getState().localAccounts.enabled).toBe(true)
  })
})
