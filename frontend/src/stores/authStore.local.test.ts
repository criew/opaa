import { describe, it, expect, beforeEach, vi } from 'vitest'
import axios from 'axios'
import { http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import { setupAuthInterceptors } from '../services/apiInterceptors'
import { LAST_SESSION_KIND_STORAGE_KEY } from '../services/authApi'
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

/** The non-secret note that gates the one refresh attempt at start-up. */
function rememberLastLocalSession() {
  localStorage.setItem(LAST_SESSION_KIND_STORAGE_KEY, 'local')
}

function setCsrfCookie() {
  document.cookie = 'XSRF-TOKEN=csrf-1; path=/'
}

/** A local session as it stands after a reload, before anything is renewed. */
function standingLocalSession() {
  rememberLastLocalSession()
  setCsrfCookie()
  useAuthStore.setState({ sessionKind: 'local', token: 'old', isAuthenticated: true })
}

/**
 * The local session of ADR-0033 in the store: restored from the refresh cookie, renewed through a
 * single shared request, ended with the CSRF double-submit header.
 */
describe('authStore - local session', () => {
  beforeEach(() => {
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

  // The CSRF cookie cannot gate this: the backend sets it on every response, so it is already
  // there after the first GET /auth/config of a signed-out visitor.
  it('makes no refresh call while this browser never held a local session', async () => {
    withLocalConfig()
    setCsrfCookie()
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
    rememberLastLocalSession()
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
    // the access token itself is never written anywhere
    expect(JSON.stringify(localStorage)).not.toContain('local-access-token')
    expect(JSON.stringify(sessionStorage)).not.toContain('local-access-token')
  })

  it('stays signed out - without a loop - and forgets the note when the refresh is refused', async () => {
    withLocalConfig()
    rememberLastLocalSession()
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
    expect(localStorage.getItem(LAST_SESSION_KIND_STORAGE_KEY)).toBeNull()
  })

  // ADR-0033, Entscheidung 8: a session that is over says why, even when the refusal only reaches
  // the identity call.
  it('names the cause when the restored session is refused by its marker', async () => {
    withLocalConfig()
    rememberLastLocalSession()
    server.use(
      http.post('/api/v1/auth/local/refresh', () => HttpResponse.json(TOKEN)),
      http.get(
        '/api/v1/auth/me',
        () =>
          new HttpResponse(null, {
            status: 401,
            headers: {
              'WWW-Authenticate':
                'Bearer error="invalid_token", error_description="account_locked:admin"',
            },
          }),
      ),
    )

    await useAuthStore.getState().initialize()

    const state = useAuthStore.getState()
    expect(state.isAuthenticated).toBe(false)
    expect(state.isLoading).toBe(false)
    expect(state.error).toMatch(/von der Systemverwaltung gesperrt/)
  })

  // A backend that is momentarily out of reach says nothing about the session: dropping the note
  // here would keep every later reload from even trying, and cost a second tab its renewal.
  it('keeps the session note when the identity call fails with a server error', async () => {
    withLocalConfig()
    rememberLastLocalSession()
    server.use(
      http.post('/api/v1/auth/local/refresh', () => HttpResponse.json(TOKEN)),
      http.get('/api/v1/auth/me', () => new HttpResponse(null, { status: 502 })),
    )

    await useAuthStore.getState().initialize()

    const state = useAuthStore.getState()
    expect(state.isAuthenticated).toBe(false)
    expect(state.error).toMatch(/vorübergehend nicht erreichbar/)
    expect(localStorage.getItem(LAST_SESSION_KIND_STORAGE_KEY)).toBe('local')
  })

  it('gives up the session note when the identity call is refused', async () => {
    withLocalConfig()
    rememberLastLocalSession()
    server.use(
      http.post('/api/v1/auth/local/refresh', () => HttpResponse.json(TOKEN)),
      http.get('/api/v1/auth/me', () => new HttpResponse(null, { status: 401 })),
    )

    await useAuthStore.getState().initialize()

    expect(localStorage.getItem(LAST_SESSION_KIND_STORAGE_KEY)).toBeNull()
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
    expect(localStorage.getItem(LAST_SESSION_KIND_STORAGE_KEY)).toBe('local')
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

  it('does not blame the credentials when only the identity call fails', async () => {
    rememberLastLocalSession()
    server.use(
      http.post('/api/v1/auth/local/login', () => HttpResponse.json(TOKEN)),
      http.get('/api/v1/auth/me', () => new HttpResponse(null, { status: 500 })),
    )

    const ok = await useAuthStore.getState().loginLocal('erika.muster@stadt.example', 'geheim')

    expect(ok).toBe(false)
    expect(useAuthStore.getState().error).toMatch(/Konto konnte nicht geladen werden/)
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
    // the server error says nothing about the session another tab may be holding
    expect(localStorage.getItem(LAST_SESSION_KIND_STORAGE_KEY)).toBe('local')
  })

  // A provider session ending says nothing about a local session in another tab of this browser.
  it('leaves the session note alone when a provider session ends', () => {
    rememberLastLocalSession()
    useAuthStore.setState({ mode: 'oidc', sessionKind: 'oidc', isAuthenticated: true, token: 't' })

    useAuthStore.getState().expireSession('unknown_issuer')

    expect(localStorage.getItem(LAST_SESSION_KIND_STORAGE_KEY)).toBe('local')
  })

  it('gives up the session note when the local session itself ends', () => {
    rememberLastLocalSession()
    useAuthStore.setState({ sessionKind: 'local', isAuthenticated: true, token: 't' })

    useAuthStore.getState().expireSession('session_revoked:admin_lock')

    expect(localStorage.getItem(LAST_SESSION_KIND_STORAGE_KEY)).toBeNull()
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
    standingLocalSession()
    let calls = 0
    server.use(
      http.post('/api/v1/auth/local/refresh', async () => {
        calls += 1
        await new Promise((resolve) => setTimeout(resolve, 10))
        return HttpResponse.json({ ...TOKEN, accessToken: 'renewed-token' })
      }),
    )

    const [first, second] = await Promise.all([
      useAuthStore.getState().renewToken(),
      useAuthStore.getState().renewToken(),
    ])

    expect(first).toBe(true)
    expect(second).toBe(true)
    expect(calls).toBe(1)
    expect(useAuthStore.getState().token).toBe('renewed-token')
  })

  // The tab-local single-flight cannot see the other tabs of the same browser, which hold the very
  // same rotating cookie - the Web Lock serialises them.
  it('serialises the refresh across tabs through a Web Lock', async () => {
    standingLocalSession()
    const requested: string[] = []
    const locks = {
      request: vi.fn(async (name: string, call: () => Promise<unknown>) => {
        requested.push(name)
        return call()
      }),
    }
    vi.stubGlobal('navigator', { ...navigator, locks })
    server.use(http.post('/api/v1/auth/local/refresh', () => HttpResponse.json(TOKEN)))

    try {
      await expect(useAuthStore.getState().renewToken()).resolves.toBe(true)
    } finally {
      vi.unstubAllGlobals()
    }

    expect(requested).toEqual(['opaa.local.refresh'])
  })

  it('reports a refused renewal instead of retrying it', async () => {
    standingLocalSession()
    server.use(
      http.post('/api/v1/auth/local/refresh', () => new HttpResponse(null, { status: 401 })),
    )

    await expect(useAuthStore.getState().renewToken()).resolves.toBe(false)
  })

  it('repeats a refresh once with a freshly primed CSRF cookie', async () => {
    standingLocalSession()
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
    expect(localStorage.getItem(LAST_SESSION_KIND_STORAGE_KEY)).toBeNull()
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
    expect(localStorage.getItem(LAST_SESSION_KIND_STORAGE_KEY)).toBeNull()
  })

  /**
   * Die öffentliche Anmeldekonfiguration ist nach jedem Sitzungsende veraltet (#1612): Wer die
   * lokale Anmeldung umschaltet und sich dann abmeldet, bekam die Anmeldeseite im Stand vom
   * Anwendungsstart zu sehen - ein Formular, das es nicht mehr gibt, oder keines, obwohl es
   * wieder eines gibt. Erst ein echtes Neuladen der Seite zeigte den wahren Stand.
   */
  it('reloads the sign-in configuration on logout, so the sign-in page needs no page reload', async () => {
    setCsrfCookie()
    let localAccountsEnabled = true
    server.use(
      http.get('/api/v1/auth/config', () =>
        HttpResponse.json({
          ...LOCAL_CONFIG,
          localAccounts: { ...LOCAL_CONFIG.localAccounts, enabled: localAccountsEnabled },
        }),
      ),
      http.post('/api/v1/auth/local/logout', () => new HttpResponse(null, { status: 204 })),
      http.get('/api/v1/auth/me', () => HttpResponse.json(ME)),
      http.post('/api/v1/auth/local/login', () => HttpResponse.json(TOKEN)),
    )
    await useAuthStore.getState().initialize()
    await useAuthStore.getState().loginLocal('erika.muster@stadt.example', 'geheim')
    expect(useAuthStore.getState().localAccounts.enabled).toBe(true)

    // Die Systemverwaltung schaltet die lokale Anmeldung ab, während diese Sitzung läuft.
    localAccountsEnabled = false
    await useAuthStore.getState().logout()

    expect(useAuthStore.getState().localAccounts.enabled).toBe(false)
  })

  it('reloads the sign-in configuration when the session ends without a logout click', async () => {
    setCsrfCookie()
    let localAccountsEnabled = true
    server.use(
      http.get('/api/v1/auth/config', () =>
        HttpResponse.json({
          ...LOCAL_CONFIG,
          localAccounts: { ...LOCAL_CONFIG.localAccounts, enabled: localAccountsEnabled },
        }),
      ),
      http.get('/api/v1/auth/me', () => HttpResponse.json(ME)),
      http.post('/api/v1/auth/local/login', () => HttpResponse.json(TOKEN)),
    )
    await useAuthStore.getState().initialize()
    await useAuthStore.getState().loginLocal('erika.muster@stadt.example', 'geheim')

    // Genau der Weg, den das Abschalten der lokalen Anmeldung für die eigene Sitzung nimmt: Sie
    // endet serverseitig, ohne dass jemand auf „Abmelden" geklickt hat.
    localAccountsEnabled = false
    useAuthStore.getState().expireSession('local_accounts_disabled')

    await vi.waitFor(() => expect(useAuthStore.getState().localAccounts.enabled).toBe(false))
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
  })

  it('keeps the last known sign-in configuration when the reload fails', async () => {
    setCsrfCookie()
    let configFails = false
    server.use(
      http.get('/api/v1/auth/config', () =>
        configFails ? new HttpResponse(null, { status: 503 }) : HttpResponse.json(LOCAL_CONFIG),
      ),
      http.post('/api/v1/auth/local/logout', () => new HttpResponse(null, { status: 204 })),
      http.get('/api/v1/auth/me', () => HttpResponse.json(ME)),
      http.post('/api/v1/auth/local/login', () => HttpResponse.json(TOKEN)),
    )
    await useAuthStore.getState().initialize()
    await useAuthStore.getState().loginLocal('erika.muster@stadt.example', 'geheim')

    configFails = true
    await useAuthStore.getState().logout()

    // Eine nicht erreichbare Konfiguration ist kein Grund, die Anmeldeseite leer zu räumen: Der
    // letzte bekannte Stand ist besser als gar keiner, und das Abmelden gilt trotzdem.
    expect(useAuthStore.getState().localAccounts.enabled).toBe(true)
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

  // The whole 401 path of a local session in one go: one shared refresh, one repeat of the
  // original request, no second attempt.
  it('answers a 401 in a local session with exactly one refresh and one repeat', async () => {
    standingLocalSession()
    useAuthStore.setState({ token: 'expired-token' })
    let refreshes = 0
    let calls = 0
    server.use(
      http.post('/api/v1/auth/local/refresh', () => {
        refreshes += 1
        return HttpResponse.json({ ...TOKEN, accessToken: 'renewed-token' })
      }),
      http.get('/api/spaces', ({ request }) => {
        calls += 1
        if (calls === 1) return new HttpResponse(null, { status: 401 })
        expect(request.headers.get('Authorization')).toBe('Bearer renewed-token')
        return HttpResponse.json({ ok: true })
      }),
    )
    const client = axios.create({ baseURL: '/api' })
    setupAuthInterceptors(
      client,
      () => useAuthStore.getState().getAccessToken(),
      () => useAuthStore.getState().renewToken(),
      (reason) => useAuthStore.getState().expireSession(reason),
      (reason) => useAuthStore.getState().requirePasswordChange(reason),
    )

    await expect(client.get('/spaces')).resolves.toBeTruthy()

    expect(refreshes).toBe(1)
    expect(calls).toBe(2)
    expect(useAuthStore.getState().isAuthenticated).toBe(true)
  })

  it('ends the session without a refresh when the 401 names a marker', async () => {
    standingLocalSession()
    let refreshes = 0
    server.use(
      http.post('/api/v1/auth/local/refresh', () => {
        refreshes += 1
        return HttpResponse.json(TOKEN)
      }),
      http.get(
        '/api/spaces',
        () =>
          new HttpResponse(null, {
            status: 401,
            headers: {
              'WWW-Authenticate':
                'Bearer error="invalid_token", error_description="local_accounts_disabled"',
            },
          }),
      ),
    )
    const client = axios.create({ baseURL: '/api' })
    setupAuthInterceptors(
      client,
      () => useAuthStore.getState().getAccessToken(),
      () => useAuthStore.getState().renewToken(),
      (reason) => useAuthStore.getState().expireSession(reason),
    )

    await expect(client.get('/spaces')).rejects.toThrow()

    expect(refreshes).toBe(0)
    expect(useAuthStore.getState().isAuthenticated).toBe(false)
    expect(useAuthStore.getState().error).toMatch(/abgeschaltet/)
  })
})
