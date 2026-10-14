import { http, HttpResponse } from 'msw'
import { mockAuthConfig, mockLocalAccount, mockLocalTokenResponse, mockUser } from './authFixtures'

export const authHandlers = [
  http.get('/api/v1/auth/config', () => {
    return HttpResponse.json(mockAuthConfig)
  }),

  // ADR-0033: the local sign-in of the mocks knows exactly one account. Every refusal is the same
  // answer, as the backend's is - unknown address and wrong password are indistinguishable.
  http.post('/api/v1/auth/local/login', async ({ request }) => {
    const body = (await request.json()) as { email?: string; password?: string }
    if (
      body.email?.trim().toLowerCase() !== mockLocalAccount.email ||
      body.password !== mockLocalAccount.password
    ) {
      return HttpResponse.json(
        {
          error: 'Anmeldung fehlgeschlagen. Prüfen Sie E-Mail-Adresse und Passwort.',
          status: 401,
          timestamp: new Date().toISOString(),
        },
        { status: 401 },
      )
    }
    return HttpResponse.json(mockLocalTokenResponse, {
      headers: { 'Set-Cookie': 'XSRF-TOKEN=mock-csrf-token; Path=/' },
    })
  }),

  http.post('/api/v1/auth/local/refresh', () => HttpResponse.json(mockLocalTokenResponse)),

  http.post('/api/v1/auth/local/logout', () => new HttpResponse(null, { status: 204 })),

  http.post('/api/v1/auth/local/change-password', async ({ request }) => {
    const body = (await request.json()) as { currentPassword?: string; newPassword?: string }
    if (body.currentPassword !== mockLocalAccount.password) {
      return HttpResponse.json(
        {
          error: 'Das aktuelle Passwort ist nicht korrekt.',
          status: 400,
          timestamp: new Date().toISOString(),
          fieldErrors: [
            {
              field: 'currentPassword',
              code: 'WRONG_PASSWORD',
              message: 'Das aktuelle Passwort ist nicht korrekt.',
            },
          ],
        },
        { status: 400 },
      )
    }
    if ((body.newPassword ?? '').length < (mockAuthConfig.localAccounts?.passwordMinLength ?? 12)) {
      return HttpResponse.json(
        {
          error: 'Das Passwort erfüllt die Vorgaben nicht.',
          status: 400,
          timestamp: new Date().toISOString(),
          fieldErrors: [{ field: 'newPassword', code: 'TOO_SHORT', message: 'Passwort zu kurz' }],
        },
        { status: 400 },
      )
    }
    return HttpResponse.json({ ...mockLocalTokenResponse, passwordChangeRequired: false })
  }),

  http.get('/api/v1/auth/me', () => {
    return HttpResponse.json(mockUser)
  }),
]
