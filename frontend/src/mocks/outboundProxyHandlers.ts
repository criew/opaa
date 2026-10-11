import { http, HttpResponse } from 'msw'
import type { OutboundProxyRequest, OutboundProxyResponse } from '../types/api'

/** What the mock stores; no proxy until one is set. */
let stored: OutboundProxyResponse = { proxyUrl: null, noProxy: [], updatedAt: null }

const PROXY_URL = /^(http:\/\/)?[a-z0-9.-]+:\d{1,5}\/?$/i
const ENTRY = /^(\*\.|\.)?[a-z0-9_.:/-]+$/i

export const outboundProxyHandlers = [
  http.get('/api/v1/admin/outbound-proxy', () => HttpResponse.json(stored)),

  http.put('/api/v1/admin/outbound-proxy', async ({ request }) => {
    const body = (await request.json()) as OutboundProxyRequest
    const proxyUrl = body.proxyUrl?.trim() || null
    const noProxy = body.noProxy.map((entry) => entry.trim().toLowerCase()).filter(Boolean)
    const fieldErrors = []
    if (proxyUrl !== null && !PROXY_URL.test(proxyUrl)) {
      fieldErrors.push({
        field: 'proxyUrl',
        code: 'INVALID',
        message: 'Die Proxy-Adresse muss die Form http://host:port oder host:port haben.',
      })
    }
    const invalid = noProxy.filter((entry) => !ENTRY.test(entry) || /:\d+$/.test(entry))
    if (invalid.length > 0) {
      fieldErrors.push({
        field: 'noProxy',
        code: 'INVALID',
        message: `Kein gültiger Eintrag der Ausnahmeliste: ${invalid.join(', ')}.`,
      })
    }
    if (fieldErrors.length > 0) {
      return HttpResponse.json(
        {
          error: 'Die Einstellungen des Ausgangsproxys sind ungültig.',
          status: 400,
          fieldErrors,
        },
        { status: 400 },
      )
    }
    stored = {
      proxyUrl: proxyUrl === null ? null : `http://${proxyUrl.replace(/^http:\/\//, '')}`,
      noProxy: noProxy.map((entry) => entry.replace(/^\*?\./, '')),
      updatedAt: new Date().toISOString(),
    }
    return HttpResponse.json(stored)
  }),
]
