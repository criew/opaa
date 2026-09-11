import { http, HttpResponse } from 'msw'
import type {
  MailSendResultResponse,
  MailSettingsUpdateRequest,
  MailTemplatePreviewRequest,
  MailTemplateResponse,
  MailTemplateUpdateRequest,
} from '../types/api'
import {
  MAIL_PASSWORD_MASK,
  mockMailSampleValues,
  mockMailSettings,
  mockMailTemplates,
  setMockMailSettings,
  toMailTemplateSummary,
} from './mailFixtures'

/**
 * The mail administration API (#1542) as MSW handlers - the invariants the page depends on, the
 * same ones `MailSettingsService` and `MailTemplateService` enforce:
 *
 * - the stored password never leaves the mock; a response carries the mask, and the mask sent
 *   back means „unverändert" while an empty string clears it,
 * - a test send answers 200 whatever happens and writes the status fields, so the status tile
 *   reflects what just happened,
 * - a placeholder the template does not declare is a 400 naming the field.
 */

/** The host that makes the mock's send attempt fail, so the FAILED path is reachable. */
export const MAIL_FAILING_HOST = 'smtp.kaputt.example'

const TEST_RECIPIENT = 'admin@opaa.local'

function placeholdersIn(text: string | null | undefined): string[] {
  return [...(text ?? '').matchAll(/\{\{\s*([A-Za-z0-9_]+)\s*\}\}/g)].map((match) => match[1])
}

function render(text: string, variables: Record<string, string>): string {
  return text.replace(
    /\{\{\s*([A-Za-z0-9_]+)\s*\}\}/g,
    (_all, name: string) => variables[name] ?? '',
  )
}

function undeclared(
  template: MailTemplateResponse,
  fields: Record<string, string | null | undefined>,
): { field: string; name: string } | null {
  for (const [field, text] of Object.entries(fields)) {
    for (const name of placeholdersIn(text)) {
      if (!template.placeholders.includes(name)) return { field, name }
    }
  }
  return null
}

function placeholderError(template: MailTemplateResponse, field: string, name: string) {
  return HttpResponse.json(
    {
      error:
        `Der Platzhalter „${name}" ist in „${field}" nicht zulässig. Erlaubt sind: ` +
        template.placeholders.join(', '),
    },
    { status: 400 },
  )
}

/** Mirrors `MailService`: never an error response, the outcome is the body. */
function sendOutcome(): MailSendResultResponse {
  const settings = mockMailSettings
  const now = new Date().toISOString()
  if (!settings.enabled || !settings.host) {
    return {
      outcome: 'SKIPPED',
      reason: 'SMTP ist nicht eingerichtet, es wurde nichts versendet.',
      recipient: null,
    }
  }
  if (settings.host === MAIL_FAILING_HOST) {
    setMockMailSettings({
      ...settings,
      lastFailureAt: now,
      lastFailureReason: 'Verbindung abgelehnt (Connection refused)',
    })
    return {
      outcome: 'FAILED',
      reason: 'Verbindung abgelehnt (Connection refused)',
      recipient: null,
    }
  }
  setMockMailSettings({ ...settings, lastSuccessAt: now })
  return { outcome: 'SENT', reason: null, recipient: TEST_RECIPIENT }
}

function findTemplate(key: string): MailTemplateResponse | undefined {
  return mockMailTemplates.find((template) => template.key === key)
}

export const mailHandlers = [
  http.get('/api/v1/system/mail-settings', () => HttpResponse.json(mockMailSettings)),

  http.put('/api/v1/system/mail-settings', async ({ request }) => {
    const body = (await request.json()) as MailSettingsUpdateRequest
    if (body.enabled && (!body.host || !body.port || !body.fromAddress)) {
      return HttpResponse.json(
        {
          error:
            'Der Versand lässt sich erst einschalten, wenn Server, Port und Absenderadresse' +
            ' gesetzt sind.',
        },
        { status: 400 },
      )
    }
    // The three-way password rule of MailSettingsService: mask keeps, empty clears, anything
    // else replaces. Whatever the outcome, only the mask is answered.
    const keepsPassword = body.password == null || body.password === MAIL_PASSWORD_MASK
    const passwordSet = keepsPassword
      ? mockMailSettings.passwordSet
      : (body.password ?? '').length > 0
    setMockMailSettings({
      ...mockMailSettings,
      enabled: body.enabled,
      host: body.host ?? null,
      port: body.port ?? null,
      username: body.username ?? null,
      encryption: body.encryption,
      fromAddress: body.fromAddress ?? null,
      fromName: body.fromName ?? null,
      passwordSet,
      password: passwordSet ? MAIL_PASSWORD_MASK : null,
      updatedAt: new Date().toISOString(),
    })
    return HttpResponse.json(mockMailSettings)
  }),

  http.post('/api/v1/system/mail-settings/test', () => HttpResponse.json(sendOutcome())),

  http.get('/api/v1/system/mail-templates', () =>
    HttpResponse.json(mockMailTemplates.map(toMailTemplateSummary)),
  ),

  http.get('/api/v1/system/mail-templates/:templateKey', ({ params }) => {
    const template = findTemplate(String(params.templateKey))
    if (!template) {
      return HttpResponse.json({ error: 'Vorlage nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json(template)
  }),

  http.put('/api/v1/system/mail-templates/:templateKey', async ({ params, request }) => {
    const template = findTemplate(String(params.templateKey))
    if (!template) {
      return HttpResponse.json({ error: 'Vorlage nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as MailTemplateUpdateRequest
    const unknown = undeclared(template, {
      Betreff: body.subject,
      Text: body.bodyPlain,
    })
    if (unknown) return placeholderError(template, unknown.field, unknown.name)

    template.subject = body.subject
    template.bodyPlain = body.bodyPlain
    template.bodyHtml = body.bodyHtml ?? null
    template.source = 'DATABASE'
    template.updatedAt = new Date().toISOString()
    template.updatedBy = '00000000-0000-0000-0000-000000000001'
    return HttpResponse.json(template)
  }),

  http.delete('/api/v1/system/mail-templates/:templateKey', ({ params }) => {
    const template = findTemplate(String(params.templateKey))
    if (!template) {
      return HttpResponse.json({ error: 'Vorlage nicht gefunden' }, { status: 404 })
    }
    template.subject = template.defaultSubject
    template.bodyPlain = template.defaultBodyPlain
    template.bodyHtml = null
    template.source = 'DEFAULT'
    template.updatedAt = null
    template.updatedBy = null
    return HttpResponse.json(template)
  }),

  http.post('/api/v1/system/mail-templates/:templateKey/preview', async ({ params, request }) => {
    const template = findTemplate(String(params.templateKey))
    if (!template) {
      return HttpResponse.json({ error: 'Vorlage nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as MailTemplatePreviewRequest
    const subject = body.subject ?? template.subject
    const bodyPlain = body.bodyPlain ?? template.bodyPlain
    const unknown = undeclared(template, { Betreff: subject, Text: bodyPlain })
    if (unknown) return placeholderError(template, unknown.field, unknown.name)

    const variables: Record<string, string> = {}
    for (const name of template.placeholders) {
      variables[name] = body.variables?.[name] ?? mockMailSampleValues[name] ?? ''
    }
    return HttpResponse.json({
      subject: render(subject, variables),
      bodyPlain: render(bodyPlain, variables),
      bodyHtml: `<div>${render(bodyPlain, variables).replace(/\n/g, '<br>')}</div>`,
      variables,
    })
  }),

  http.post('/api/v1/system/mail-templates/:templateKey/test', ({ params }) => {
    if (!findTemplate(String(params.templateKey))) {
      return HttpResponse.json({ error: 'Vorlage nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json(sendOutcome())
  }),
]
