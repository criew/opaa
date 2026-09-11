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
 * - an unknown placeholder and an unsupported tag form ({{{x}}}, {{#x}}, …) are a 400
 *   naming the field, with the message the backend itself produces.
 */

/** The host that makes the mock's send attempt fail, so the FAILED path is reachable. */
export const MAIL_FAILING_HOST = 'smtp.kaputt.example'

const TEST_RECIPIENT = 'admin@opaa.local'

/**
 * The tag grammar of `MailPlaceholderValidator`: a triple-stache (captured on its own) or an
 * ordinary double-stache. Kept in step with the backend so the mock rejects exactly what the
 * backend rejects - a mock that is more permissive would let a template through here that fails
 * in production.
 */
const TEMPLATE_TAG = /(\{\{\{\s*[^{}]*?\s*\}\}\})|\{\{\s*([^{}]*?)\s*\}\}/g

/** Tag prefixes that are neither a plain variable nor a comment (sections, partials, unescaped). */
const UNSUPPORTED_PREFIXES = '>#/^&='

function braced(names: string[]): string {
  return names.map((name) => `{{${name}}}`).join(', ')
}

/** The plain variable names referenced in `content`; comments and unsupported forms are skipped. */
function placeholdersIn(text: string | null | undefined): string[] {
  const names = new Set<string>()
  for (const match of (text ?? '').matchAll(TEMPLATE_TAG)) {
    const inner = match[2]?.trim()
    if (!inner || inner.startsWith('!') || UNSUPPORTED_PREFIXES.includes(inner[0])) continue
    names.add(inner)
  }
  return [...names].sort()
}

function render(text: string, variables: Record<string, string>): string {
  return text.replace(
    /\{\{\s*([A-Za-z0-9_]+)\s*\}\}/g,
    (_all, name: string) => variables[name] ?? '',
  )
}

/** `MailTemplateService#requireUsableContent`, in the order the backend checks the two rules. */
function contentError(
  template: MailTemplateResponse,
  fields: Record<string, string | null | undefined>,
): string | null {
  for (const [fieldLabel, text] of Object.entries(fields)) {
    for (const match of (text ?? '').matchAll(TEMPLATE_TAG)) {
      const inner = match[2]?.trim() ?? ''
      // An empty tag is named explicitly rather than falling out of includes(''), which is true
      // for every string: {{}} has no variable name for the renderer to resolve and fails the
      // backend's probe render, so the mock must not let it through either.
      const unsupported =
        match[1] ??
        (match[2] !== undefined && (inner === '' || UNSUPPORTED_PREFIXES.includes(inner[0]))
          ? match[0]
          : null)
      if (unsupported) {
        return (
          `${fieldLabel}: Nicht unterstützte Vorlagen-Syntax ${unsupported.trim()}. Erlaubt sind` +
          ' nur einfache Platzhalter der Form {{name}} und Kommentare der Form {{! ... }}'
        )
      }
    }
    const unknown = placeholdersIn(text).filter((name) => !template.placeholders.includes(name))
    if (unknown.length > 0) {
      return (
        `${fieldLabel}: Unbekannte Platzhalter ${braced(unknown)}.` +
        ` Erlaubt sind ${braced(template.placeholders)}`
      )
    }
  }
  return null
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
    // minLength: 1 of MailTemplateUpdateRequest - without it the mock would accept a template the
    // backend rejects, and the editor's own guard would never be exercised.
    if (!body.subject?.trim() || !body.bodyPlain?.trim()) {
      return HttpResponse.json(
        { error: 'Betreff und Text dürfen nicht leer sein.' },
        { status: 400 },
      )
    }
    // bodyHtml included: the editor sends it back on every PUT, so an override stored through
    // the API has to pass the same check as the two fields the editor itself writes.
    const invalid = contentError(template, {
      subject: body.subject,
      bodyPlain: body.bodyPlain,
      bodyHtml: body.bodyHtml,
    })
    if (invalid) return HttpResponse.json({ error: invalid }, { status: 400 })

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
    const invalid = contentError(template, { subject, bodyPlain, bodyHtml: body.bodyHtml })
    if (invalid) return HttpResponse.json({ error: invalid }, { status: 400 })

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
