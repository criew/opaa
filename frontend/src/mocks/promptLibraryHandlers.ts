import { http, HttpResponse } from 'msw'
import type {
  PromptLibraryRequest,
  PromptLibraryResponse,
  PromptLibraryUpdateRequest,
  PromptRequest,
  PromptResponse,
} from '../types/api'
import { mockGroups, mockMyCapabilities, mockMyGroups } from './fixtures'
import {
  mockPromptLibraries,
  mockPrompts,
  mockUnreadablePromptLibraryIds,
} from './promptLibraryFixtures'

const ROLE_ORDER = ['VIEWER', 'EDITOR', 'MANAGER', 'OWNER'] as const

function holds(library: PromptLibraryResponse, minimum: (typeof ROLE_ORDER)[number]): boolean {
  return ROLE_ORDER.indexOf(library.myRole) >= ROLE_ORDER.indexOf(minimum)
}

function notFound() {
  return HttpResponse.json({ error: 'Prompt-Bibliothek nicht gefunden' }, { status: 404 })
}

const VISIBILITY_ORDER = ['PRIVATE', 'SHARED', 'ORGANIZATION'] as const

/** The server's text form: blanks inside the braces removed, system variables upper case. */
function normalizeText(text: string): string {
  return text.replace(/\{\{\s*([A-Za-z][A-Za-z0-9_]*)\s*\}\}/g, (_match, name: string) => {
    const upper = name.toUpperCase()
    return `{{${upper === 'CURRENT_DATE' || upper === 'USER_NAME' ? upper : name}}}`
  })
}

function duplicateName(name: string) {
  return HttpResponse.json(
    { error: `In dieser Prompt-Bibliothek gibt es bereits einen Prompt mit dem Namen „${name}“.` },
    { status: 409 },
  )
}

function forbidden() {
  return HttpResponse.json({ error: 'Kein Zugriff auf diese Prompt-Bibliothek' }, { status: 403 })
}

/** A subset of the backend's PromptTemplate/PromptService checks, same wording. */
function validatePrompt(body: PromptRequest): string | null {
  if (!/^[a-z0-9]+(-[a-z0-9]+)*$/.test(body.name ?? '')) {
    return 'Der Name eines Prompts besteht aus Kleinbuchstaben, Ziffern und einzelnen Bindestrichen, höchstens 64 Zeichen.'
  }
  if (!body.title?.trim()) return 'Der Titel ist erforderlich und hat höchstens 255 Zeichen.'
  if (!body.text?.trim()) return 'Der Text ist erforderlich und hat höchstens 8000 Zeichen.'
  const used = new Set<string>()
  for (const match of body.text.matchAll(/\{\{([\s\S]*?)\}\}/g)) {
    const name = match[1].trim()
    if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(name)) {
      return `Ungültiger Platzhalter „{{${name}}}“: Ein Variablenname beginnt mit einem Buchstaben und enthält nur Buchstaben, Ziffern und Unterstriche.`
    }
    const upper = name.toUpperCase()
    used.add(upper === 'CURRENT_DATE' || upper === 'USER_NAME' ? upper : name)
  }
  const defined = new Set((body.variables ?? []).map((variable) => variable.name))
  for (const name of used) {
    if (name !== 'CURRENT_DATE' && name !== 'USER_NAME' && !defined.has(name)) {
      return `Die Variable „{{${name}}}“ wird im Text verwendet, ist aber nicht definiert.`
    }
  }
  for (const name of defined) {
    if (!used.has(name)) {
      return `Die Variable „${name}“ ist definiert, wird im Text aber nicht verwendet.`
    }
  }
  return null
}

function refreshPromptCount(libraryId: string) {
  const library = mockPromptLibraries[libraryId]
  if (library) library.promptCount = (mockPrompts[libraryId] ?? []).length
}

/** The prompt library endpoints for the mocked frontend. */
export const promptLibraryHandlers = [
  // The list holds what the caller may read; a library reached only through administration is not
  // in it.
  http.get('/api/v1/prompt-libraries', () =>
    HttpResponse.json(
      Object.values(mockPromptLibraries)
        .filter((library) => !mockUnreadablePromptLibraryIds.has(library.id))
        .sort((a, b) => a.name.localeCompare(b.name)),
    ),
  ),

  http.post('/api/v1/prompt-libraries', async ({ request }) => {
    if (!mockMyCapabilities.capabilities.includes('CREATE_PROMPT_LIBRARY')) {
      return HttpResponse.json(
        {
          error:
            'Ihnen fehlt das Anlegerecht „Prompt-Bibliotheken anlegen“. Wenden Sie sich an die Systemverwaltung, wenn Sie es benötigen.',
          code: 'CAPABILITY_REQUIRED',
        },
        { status: 403 },
      )
    }
    const body = (await request.json()) as PromptLibraryRequest
    if (!body.name?.trim()) {
      return HttpResponse.json({ error: 'Name ist erforderlich' }, { status: 400 })
    }
    const group =
      body.ownerType === 'GROUP' ? mockGroups.find((g) => g.id === body.ownerId) : undefined
    if (body.ownerType === 'GROUP' && !group) {
      return HttpResponse.json(
        { error: 'ownerId ist erforderlich, wenn ownerType GROUP ist' },
        { status: 400 },
      )
    }
    if (group && !mockMyGroups.some((mine) => mine.id === group.id)) {
      return HttpResponse.json(
        { error: 'Nur Mitglieder der Gruppe können eine Prompt-Bibliothek in ihrem Namen anlegen' },
        { status: 403 },
      )
    }
    const now = new Date().toISOString()
    const library: PromptLibraryResponse = {
      id: `prompt-library-${crypto.randomUUID().slice(0, 8)}`,
      name: body.name.trim(),
      description: body.description ?? null,
      ownerType: body.ownerType ?? 'USER',
      ownerId: group?.id ?? 'mock-user-id',
      ownerName: group?.name ?? 'Mock User',
      visibility: body.visibility ?? 'PRIVATE',
      listed: body.listed ?? false,
      // The creator owns a personal library; a group owner holds MANAGER and so does its member.
      myRole: body.ownerType === 'GROUP' ? 'MANAGER' : 'OWNER',
      promptCount: 0,
      succession: null,
      createdAt: now,
      updatedAt: now,
    }
    mockPromptLibraries[library.id] = library
    mockPrompts[library.id] = []
    return HttpResponse.json(library, { status: 201 })
  }),

  http.get('/api/v1/prompt-libraries/:id', ({ params }) => {
    const library = mockPromptLibraries[String(params.id)]
    return library ? HttpResponse.json(library) : notFound()
  }),

  http.put('/api/v1/prompt-libraries/:id', async ({ params, request }) => {
    const library = mockPromptLibraries[String(params.id)]
    if (!library) return notFound()
    if (!holds(library, 'MANAGER')) return forbidden()
    const body = (await request.json()) as PromptLibraryUpdateRequest
    const widens =
      VISIBILITY_ORDER.indexOf(body.visibility) > VISIBILITY_ORDER.indexOf(library.visibility) ||
      (body.listed && !library.listed)
    if (library.succession && widens) {
      return HttpResponse.json(
        {
          error: `Für dieses Objekt ist die Nachfolge offen: eine größere Reichweite (Sichtbarkeit oder Auffindbarkeit) ist deshalb nicht möglich. Bestehende Rechte bleiben unverändert, und nichts wird gelöscht. Zuständig: ${library.succession.addresseeLabel}`,
          code: 'SUCCESSION_OPEN',
        },
        { status: 409 },
      )
    }
    Object.assign(library, {
      name: body.name,
      description: body.description ?? null,
      visibility: body.visibility,
      listed: body.listed,
      updatedAt: new Date().toISOString(),
    })
    return HttpResponse.json(library)
  }),

  http.delete('/api/v1/prompt-libraries/:id', ({ params }) => {
    const id = String(params.id)
    const library = mockPromptLibraries[id]
    if (!library) return notFound()
    if (!holds(library, 'OWNER')) return forbidden()
    delete mockPromptLibraries[id]
    delete mockPrompts[id]
    return new HttpResponse(null, { status: 204 })
  }),

  http.get('/api/v1/prompt-libraries/:id/prompts', ({ params }) => {
    const id = String(params.id)
    if (!mockPromptLibraries[id]) return notFound()
    // Administering a library is not reading it: without a right of the formula the prompts stay
    // closed, as they do on the server.
    if (mockUnreadablePromptLibraryIds.has(id)) {
      return HttpResponse.json(
        { error: 'Kein Zugriff auf die Prompts dieser Prompt-Bibliothek' },
        { status: 403 },
      )
    }
    return HttpResponse.json(mockPrompts[id] ?? [])
  }),

  http.post('/api/v1/prompt-libraries/:id/prompts', async ({ params, request }) => {
    const id = String(params.id)
    const library = mockPromptLibraries[id]
    if (!library) return notFound()
    if (!holds(library, 'EDITOR')) return forbidden()
    const body = (await request.json()) as PromptRequest
    const invalid = validatePrompt(body)
    if (invalid) return HttpResponse.json({ error: invalid }, { status: 400 })
    const prompts = mockPrompts[id] ?? []
    if (prompts.some((prompt) => prompt.name === body.name)) return duplicateName(body.name)
    const now = new Date().toISOString()
    const prompt: PromptResponse = {
      id: `prompt-${crypto.randomUUID().slice(0, 8)}`,
      promptLibraryId: id,
      name: body.name,
      title: body.title,
      description: body.description ?? null,
      text: normalizeText(body.text),
      variables: body.variables ?? [],
      sortOrder: body.sortOrder ?? 0,
      createdAt: now,
      updatedAt: now,
    }
    mockPrompts[id] = [...prompts, prompt]
    refreshPromptCount(id)
    return HttpResponse.json(prompt, { status: 201 })
  }),

  http.put('/api/v1/prompt-libraries/:id/prompts/:promptId', async ({ params, request }) => {
    const id = String(params.id)
    const library = mockPromptLibraries[id]
    if (!library) return notFound()
    if (!holds(library, 'EDITOR')) return forbidden()
    const prompts = mockPrompts[id] ?? []
    const existing = prompts.find((prompt) => prompt.id === String(params.promptId))
    if (!existing) return HttpResponse.json({ error: 'Prompt nicht gefunden' }, { status: 404 })
    const body = (await request.json()) as PromptRequest
    const invalid = validatePrompt(body)
    if (invalid) return HttpResponse.json({ error: invalid }, { status: 400 })
    if (prompts.some((prompt) => prompt.id !== existing.id && prompt.name === body.name)) {
      return duplicateName(body.name)
    }
    const updated: PromptResponse = {
      ...existing,
      name: body.name,
      title: body.title,
      description: body.description ?? null,
      text: normalizeText(body.text),
      variables: body.variables ?? [],
      sortOrder: body.sortOrder ?? existing.sortOrder,
      updatedAt: new Date().toISOString(),
    }
    mockPrompts[id] = prompts.map((prompt) => (prompt.id === existing.id ? updated : prompt))
    return HttpResponse.json(updated)
  }),

  http.delete('/api/v1/prompt-libraries/:id/prompts/:promptId', ({ params }) => {
    const id = String(params.id)
    const library = mockPromptLibraries[id]
    if (!library) return notFound()
    if (!holds(library, 'EDITOR')) return forbidden()
    mockPrompts[id] = (mockPrompts[id] ?? []).filter(
      (prompt) => prompt.id !== String(params.promptId),
    )
    refreshPromptCount(id)
    return new HttpResponse(null, { status: 204 })
  }),
]
