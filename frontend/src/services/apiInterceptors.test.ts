import axios from 'axios'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { server } from '../mocks/server'
import { setupAuthInterceptors } from './apiInterceptors'

/**
 * #737: a 401 used to expire the whole session immediately (via a single onUnauthorized callback
 * that always meant "log the user out"), including background polls (indexingStore/documentStore)
 * that fire without any user action - a briefly expired access token felt like a random logout.
 * These tests exercise setupAuthInterceptors directly against a throwaway axios instance and msw
 * route - independent of authStore - to pin down the retry contract: one silent-renew attempt
 * with a request retry, and only a *second* 401 (or a failed renew) reaching onSessionExpired.
 */
describe('setupAuthInterceptors', () => {
  it('retries the request with a renewed token after a single 401, without expiring the session', async () => {
    let callCount = 0
    server.use(
      http.get('/api/test-retry', ({ request }) => {
        callCount += 1
        const auth = request.headers.get('Authorization')
        if (callCount === 1) {
          expect(auth).toBe('Bearer expired-token')
          return new HttpResponse(null, { status: 401 })
        }
        expect(auth).toBe('Bearer renewed-token')
        return HttpResponse.json({ ok: true })
      }),
    )

    const client = axios.create({ baseURL: '/api' })
    let currentToken = 'expired-token'
    const renewToken = vi.fn(async () => {
      currentToken = 'renewed-token'
      return true
    })
    const onSessionExpired = vi.fn()

    setupAuthInterceptors(client, () => currentToken, renewToken, onSessionExpired)

    const { data } = await client.get('/test-retry')

    expect(data).toEqual({ ok: true })
    expect(callCount).toBe(2)
    expect(renewToken).toHaveBeenCalledTimes(1)
    expect(onSessionExpired).not.toHaveBeenCalled()
  })

  it('expires the session locally when the silent renew itself fails, without retrying forever', async () => {
    server.use(http.get('/api/test-renew-fails', () => new HttpResponse(null, { status: 401 })))

    const client = axios.create({ baseURL: '/api' })
    const renewToken = vi.fn(async () => false)
    const onSessionExpired = vi.fn()

    setupAuthInterceptors(client, () => 'expired-token', renewToken, onSessionExpired)

    await expect(client.get('/test-renew-fails')).rejects.toThrow()

    expect(renewToken).toHaveBeenCalledTimes(1)
    expect(onSessionExpired).toHaveBeenCalledTimes(1)
  })

  it('expires the session when the retried request itself gets a second 401, instead of retrying again', async () => {
    let callCount = 0
    server.use(
      http.get('/api/test-still-401', () => {
        callCount += 1
        return new HttpResponse(null, { status: 401 })
      }),
    )

    const client = axios.create({ baseURL: '/api' })
    const renewToken = vi.fn(async () => true)
    const onSessionExpired = vi.fn()

    setupAuthInterceptors(client, () => 'some-token', renewToken, onSessionExpired)

    await expect(client.get('/test-still-401')).rejects.toThrow()

    expect(callCount).toBe(2)
    expect(renewToken).toHaveBeenCalledTimes(1)
    expect(onSessionExpired).toHaveBeenCalledTimes(1)
  })

  // ADR-0025: unknown_issuer means the provider of this session was disabled or deleted - a
  // renewed token would carry the same issuer, so no renew is attempted and the reason is passed
  // on for the matching explanation.
  it('expires the session without a renew when the 401 names an unknown issuer', async () => {
    server.use(
      http.get(
        '/api/test-unknown-issuer',
        () =>
          new HttpResponse(null, {
            status: 401,
            headers: {
              'WWW-Authenticate':
                'Bearer error="invalid_token", error_description="unknown_issuer"',
            },
          }),
      ),
    )

    const client = axios.create({ baseURL: '/api' })
    const renewToken = vi.fn(async () => true)
    const onSessionExpired = vi.fn()

    setupAuthInterceptors(client, () => 'token', renewToken, onSessionExpired)

    await expect(client.get('/test-unknown-issuer')).rejects.toThrow()

    expect(renewToken).not.toHaveBeenCalled()
    expect(onSessionExpired).toHaveBeenCalledWith('unknown_issuer')
  })
  // ADR-0033, Entscheidung 8: every marker of the challenge names a cause a renewal can never fix.
  it.each([
    ['local_accounts_disabled', 'local_accounts_disabled'],
    ['account_locked:failed_logins', 'account_locked:failed_logins'],
    ['account_expired', 'account_expired'],
    ['session_revoked:password_changed', 'session_revoked:password_changed'],
    ['account_not_active', 'account_not_active'],
    ['unknown_account', 'unknown_account'],
    ['malformed_token', 'malformed_token'],
  ])('ends the session without a renew when the 401 names %s', async (marker, expected) => {
    server.use(
      http.post(
        '/api/test-marker',
        () =>
          new HttpResponse(null, {
            status: 401,
            headers: {
              'WWW-Authenticate': `Bearer error="invalid_token", error_description="${marker}"`,
            },
          }),
      ),
    )

    const client = axios.create({ baseURL: '/api' })
    const renewToken = vi.fn(async () => true)
    const onSessionExpired = vi.fn()

    setupAuthInterceptors(client, () => 'token', renewToken, onSessionExpired)

    await expect(client.post('/test-marker')).rejects.toThrow()

    expect(renewToken).not.toHaveBeenCalled()
    expect(onSessionExpired).toHaveBeenCalledWith(expected)
  })

  it('still renews on a 401 whose challenge names no session-ending marker', async () => {
    let callCount = 0
    server.use(
      http.get('/api/test-plain-401', () => {
        callCount += 1
        return callCount === 1
          ? new HttpResponse(null, {
              status: 401,
              headers: { 'WWW-Authenticate': 'Bearer error="invalid_token"' },
            })
          : HttpResponse.json({ ok: true })
      }),
    )

    const client = axios.create({ baseURL: '/api' })
    const renewToken = vi.fn(async () => true)
    const onSessionExpired = vi.fn()

    setupAuthInterceptors(client, () => 'token', renewToken, onSessionExpired)

    await expect(client.get('/test-plain-401')).resolves.toBeTruthy()
    expect(renewToken).toHaveBeenCalledTimes(1)
    expect(onSessionExpired).not.toHaveBeenCalled()
  })

  // ADR-0033, Entscheidung 8: the session is intact - only the destination changes.
  it('reports a forced password change with its reason on a 403', async () => {
    server.use(
      http.get('/api/test-pcr', () =>
        HttpResponse.json(
          {
            error: 'Passwortwechsel erforderlich',
            status: 403,
            timestamp: '2026-09-11T10:00:00Z',
            code: 'PASSWORD_CHANGE_REQUIRED',
            reason: 'ADMIN_RESET',
          },
          { status: 403 },
        ),
      ),
    )

    const client = axios.create({ baseURL: '/api' })
    const renewToken = vi.fn(async () => true)
    const onSessionExpired = vi.fn()
    const onPasswordChangeRequired = vi.fn()

    setupAuthInterceptors(
      client,
      () => 'token',
      renewToken,
      onSessionExpired,
      onPasswordChangeRequired,
    )

    await expect(client.get('/test-pcr')).rejects.toThrow()

    expect(onPasswordChangeRequired).toHaveBeenCalledWith('ADMIN_RESET')
    expect(onSessionExpired).not.toHaveBeenCalled()
    expect(renewToken).not.toHaveBeenCalled()
  })

  it('leaves an ordinary 403 alone', async () => {
    server.use(
      http.get('/api/test-plain-403', () =>
        HttpResponse.json(
          { error: 'Keine Berechtigung', status: 403, timestamp: '2026-09-11T10:00:00Z' },
          { status: 403 },
        ),
      ),
    )

    const client = axios.create({ baseURL: '/api' })
    const onPasswordChangeRequired = vi.fn()

    setupAuthInterceptors(
      client,
      () => 'token',
      vi.fn(async () => true),
      vi.fn(),
      onPasswordChangeRequired,
    )

    await expect(client.get('/test-plain-403')).rejects.toThrow()
    expect(onPasswordChangeRequired).not.toHaveBeenCalled()
  })
})
