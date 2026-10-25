import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import axios from 'axios'
import { http, HttpResponse } from 'msw'
import { installInPageMocks } from './inPageMocks'

const handlers = [
  http.get('/api/v1/things', () => HttpResponse.json([{ id: 'a' }])),
  http.post('/api/v1/things', async ({ request }) =>
    HttpResponse.json({ received: await request.json() }, { status: 201 }),
  ),
  http.get('/api/v1/broken', () =>
    HttpResponse.json({ error: 'kaputt' }, { status: 502, statusText: 'Bad Gateway' }),
  ),
]

describe('installInPageMocks', () => {
  const originalFetch = window.fetch
  const originalXhr = window.XMLHttpRequest
  const network = vi.fn(async () => new Response('from network', { status: 200 }))

  beforeEach(() => {
    network.mockClear()
    Object.defineProperty(window, 'fetch', { value: network, configurable: true, writable: true })
    installInPageMocks(handlers)
  })

  afterEach(() => {
    Object.defineProperty(window, 'fetch', {
      value: originalFetch,
      configurable: true,
      writable: true,
    })
    Object.defineProperty(window, 'XMLHttpRequest', {
      value: originalXhr,
      configurable: true,
      writable: true,
    })
  })

  it('answers fetch from the handlers', async () => {
    const response = await window.fetch('/api/v1/things')
    expect(await response.json()).toEqual([{ id: 'a' }])
    expect(network).not.toHaveBeenCalled()
  })

  it('passes requests no handler answers to the original fetch', async () => {
    const response = await window.fetch('/assets/font.woff2')
    expect(await response.text()).toBe('from network')
    expect(network).toHaveBeenCalledTimes(1)
  })

  it('answers axios requests over XMLHttpRequest, body and status included', async () => {
    const client = axios.create({ baseURL: '/api/v1', adapter: 'xhr' })
    const created = await client.post('/things', { name: 'neu' })
    expect(created.status).toBe(201)
    expect(created.data).toEqual({ received: { name: 'neu' } })
    expect(created.headers['content-type']).toContain('application/json')

    await expect(client.get('/broken')).rejects.toMatchObject({
      response: { status: 502, data: { error: 'kaputt' } },
    })
    expect(network).not.toHaveBeenCalled()
  })

  it('delivers blobs for responseType blob', async () => {
    const client = axios.create({ baseURL: '/api/v1', adapter: 'xhr' })
    const response = await client.get('/things', { responseType: 'blob' })
    expect(response.data.type).toBe('application/json')
    expect(JSON.parse(await (response.data as Blob).text())).toEqual([{ id: 'a' }])
  })
})
