import { http, HttpResponse } from 'msw'
import { responsibilityOf } from '@/features/agent/mocks/agentHandlers'
import type {
  AssetLockRequest,
  DocumentTemplateResponse,
  DocumentTemplateSettingsRequest,
  DocumentTemplateUpdateRequest,
} from '@/shared/api/types'
import { mockDocumentTemplateState as state } from './documentTemplateFixtures'

function notFound() {
  return HttpResponse.json({ error: 'Objekt nicht gefunden' }, { status: 404 })
}

/** Duck-typed: in the tests the form's file comes from another realm than `File`. */
function invalidFile(file: FormDataEntryValue | null) {
  if (typeof file !== 'object' || file === null || !file.name.toLowerCase().endsWith('.docx')) {
    return HttpResponse.json(
      {
        error: 'Die Datei ist kein gültiges Word-Dokument (.docx).',
        status: 400,
        timestamp: new Date().toISOString(),
      },
      { status: 400 },
    )
  }
  return null
}

/** Like the backend: switched off, every template path answers 404. */
function enabledOr404() {
  return state.settings.enabled ? null : notFound()
}

export const documentTemplateHandlers = [
  http.get('/api/v1/document-templates/availability', () =>
    HttpResponse.json({ enabled: state.settings.enabled }),
  ),

  http.get('/api/v1/system/document-templates', () => HttpResponse.json(state.settings)),

  http.put('/api/v1/system/document-templates', async ({ request }) => {
    const body = (await request.json()) as DocumentTemplateSettingsRequest
    state.settings = {
      enabled: body.enabled,
      updatedAt: new Date().toISOString(),
      updatedBy: 'Max Mustermann',
    }
    return HttpResponse.json(state.settings)
  }),

  http.get('/api/v1/document-templates', () => {
    const off = enabledOr404()
    if (off) return off
    return HttpResponse.json(Object.values(state.templates))
  }),

  http.post('/api/v1/document-templates', async ({ request }) => {
    const off = enabledOr404()
    if (off) return off
    const form = await request.formData()
    const file = form.get('file')
    const rejected = invalidFile(file)
    if (rejected) return rejected
    const responsibility = responsibilityOf({
      groupId: (form.get('responsibleGroupId') as string | null) ?? null,
      function: (form.get('responsibleFunction') as string | null) ?? null,
    })
    if (!responsibility) {
      return HttpResponse.json(
        {
          error: 'Bitte prüfen Sie die markierten Angaben.',
          fieldErrors: [
            {
              field: 'responsible',
              code: 'REQUIRED',
              message:
                'Geben Sie die verantwortliche Stelle an: eine Gruppe, Organisationseinheit oder Funktion.',
            },
          ],
        },
        { status: 400 },
      )
    }
    const now = new Date().toISOString()
    const id = `document-template-${Date.now()}`
    const created: DocumentTemplateResponse = {
      id,
      name: String(form.get('name') ?? ''),
      description: (form.get('description') as string | null) ?? null,
      ownerType: form.get('ownerType') === 'GROUP' ? 'GROUP' : 'USER',
      ownerId: (form.get('ownerId') as string | null) ?? 'mock-user-id',
      ownerName: 'Max Mustermann',
      reach: { allAccounts: false, groupCount: 0, userCount: 1 },
      myRole: 'OWNER',
      responsibility,
      fileName: (file as File).name,
      sizeBytes: (file as File).size,
      placeholders: [{ name: 'betreff', kind: 'FIELD', fields: [] }],
      succession: null,
      createdAt: now,
      updatedAt: now,
    }
    state.templates[id] = created
    return HttpResponse.json(created, { status: 201 })
  }),

  http.get('/api/v1/document-templates/:id', ({ params }) => {
    const template = state.templates[params.id as string]
    return enabledOr404() ?? (template ? HttpResponse.json(template) : notFound())
  }),

  http.put('/api/v1/document-templates/:id', async ({ params, request }) => {
    const template = state.templates[params.id as string]
    const off = enabledOr404()
    if (off || !template) return off ?? notFound()
    const body = (await request.json()) as DocumentTemplateUpdateRequest
    const updated = {
      ...template,
      name: body.name,
      description: body.description ?? null,
      responsibility: body.responsible
        ? { ...template.responsibility, ...responsibilityOf(body.responsible) }
        : template.responsibility,
      updatedAt: new Date().toISOString(),
    }
    state.templates[template.id] = updated
    return HttpResponse.json(updated)
  }),

  http.put('/api/v1/document-templates/:id/lock', async ({ params, request }) => {
    const template = state.templates[params.id as string]
    const off = enabledOr404()
    if (off || !template) return off ?? notFound()
    const body = (await request.json()) as AssetLockRequest
    template.responsibility = {
      ...template.responsibility,
      lock: { cause: 'MANUAL', reason: body.reason, lockedAt: new Date().toISOString() },
    }
    return HttpResponse.json(template.responsibility)
  }),

  http.delete('/api/v1/document-templates/:id/lock', ({ params }) => {
    const template = state.templates[params.id as string]
    const off = enabledOr404()
    if (off || !template) return off ?? notFound()
    template.responsibility = { ...template.responsibility, lock: null }
    return HttpResponse.json(template.responsibility)
  }),

  http.delete('/api/v1/document-templates/:id', ({ params }) => {
    const off = enabledOr404()
    if (off || !state.templates[params.id as string]) return off ?? notFound()
    delete state.templates[params.id as string]
    return new HttpResponse(null, { status: 204 })
  }),

  http.get('/api/v1/document-templates/:id/file', ({ params }) => {
    const template = state.templates[params.id as string]
    const off = enabledOr404()
    if (off || !template) return off ?? notFound()
    return new HttpResponse(new Blob(['PK']), {
      headers: {
        'Content-Type': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      },
    })
  }),

  http.put('/api/v1/document-templates/:id/file', async ({ params, request }) => {
    const template = state.templates[params.id as string]
    const off = enabledOr404()
    if (off || !template) return off ?? notFound()
    const file = (await request.formData()).get('file')
    const rejected = invalidFile(file)
    if (rejected) return rejected
    const updated = {
      ...template,
      fileName: (file as File).name,
      sizeBytes: (file as File).size,
      updatedAt: new Date().toISOString(),
    }
    state.templates[template.id] = updated
    return HttpResponse.json(updated)
  }),
]
