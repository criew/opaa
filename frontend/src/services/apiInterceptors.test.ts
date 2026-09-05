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
})
