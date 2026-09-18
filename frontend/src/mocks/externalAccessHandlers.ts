import { http, HttpResponse } from 'msw'
import type {
  ExternalAccessSettingsResponse,
  ExternalAccessSettingsUpdateRequest,
} from '../types/api'

/**
 * Die Kanaleinstellungen der Fremdzugänge (#1717) als MSW-Handler - mit den Invarianten, auf die
 * sich die Seite verlässt und die `ExternalAccessSettingsService` ebenso durchsetzt: die
 * Voreinstellung ist „aus“ mit dem Hausnetz, ein PUT ersetzt alle Werte und ist beim nächsten GET
 * sichtbar, und eine ungültige Netzangabe oder eine Ablauf-Obergrenze außerhalb 1-365 ist ein 400
 * mit `fieldErrors`.
 */

export const DEFAULT_SERVER_INSTRUCTIONS =
  'Bei Fragen zu Verwaltungsvorgängen dieser Behörde zuerst „search“ aufrufen. Antworten mit' +
  ' Fundstellen belegen und die Herkunft angeben (Bibliothek, Dokument, Fundstelle). Nichts' +
  ' erfinden, was die Treffer nicht hergeben.'

function initialSettings(): ExternalAccessSettingsResponse {
  return {
    enabled: false,
    tokenMaxLifetimeDays: 90,
    tokenRateLimitPerHour: 60,
    allowedCidrs: [
      '10.0.0.0/8',
      '172.16.0.0/12',
      '192.168.0.0/16',
      '127.0.0.0/8',
      '::1/128',
      'fc00::/7',
    ],
    massRetrievalAlertThreshold: 600,
    serverInstructions: DEFAULT_SERVER_INSTRUCTIONS,
    defaultServerInstructions: DEFAULT_SERVER_INSTRUCTIONS,
    updatedAt: '2026-09-18T12:00:00Z',
    updatedBy: null,
  }
}

export let mockExternalAccessSettings: ExternalAccessSettingsResponse = initialSettings()

export function resetMockExternalAccessSettings() {
  mockExternalAccessSettings = initialSettings()
}

export function setMockExternalAccessSettings(settings: ExternalAccessSettingsResponse) {
  mockExternalAccessSettings = settings
}

/** Mirrors `CidrList.isValid`: a literal address with an optional prefix, never a host name. */
const CIDR = /^(\d{1,3}(\.\d{1,3}){3}|[0-9a-fA-F:]*:[0-9a-fA-F:.]*)(\/\d{1,3})?$/

function fieldError(field: string, message: string) {
  return HttpResponse.json(
    {
      error:
        'Die Einstellungen der Fremdzugänge sind unvollständig oder außerhalb der zulässigen Grenzen.',
      fieldErrors: [{ field, code: 'INVALID', message }],
    },
    { status: 400 },
  )
}

export const externalAccessHandlers = [
  http.get('*/api/v1/system/external-access', () => HttpResponse.json(mockExternalAccessSettings)),

  http.put('*/api/v1/system/external-access', async ({ request }) => {
    const body = (await request.json()) as ExternalAccessSettingsUpdateRequest
    if (body.tokenMaxLifetimeDays < 1 || body.tokenMaxLifetimeDays > 365) {
      return fieldError('tokenMaxLifetimeDays', 'Der Wert muss zwischen 1 und 365 liegen.')
    }
    const invalid = body.allowedCidrs.filter((cidr) => !CIDR.test(cidr))
    if (invalid.length > 0) {
      return fieldError('allowedCidrs', `Keine gültige Netzangabe: ${invalid.join(', ')}.`)
    }
    mockExternalAccessSettings = {
      ...mockExternalAccessSettings,
      enabled: body.enabled,
      tokenMaxLifetimeDays: body.tokenMaxLifetimeDays,
      tokenRateLimitPerHour: body.tokenRateLimitPerHour,
      allowedCidrs: body.allowedCidrs,
      massRetrievalAlertThreshold: body.massRetrievalAlertThreshold,
      serverInstructions: body.serverInstructions,
      updatedAt: '2026-09-18T12:30:00Z',
      updatedBy: 'Dev Admin',
    }
    return HttpResponse.json(mockExternalAccessSettings)
  }),
]
