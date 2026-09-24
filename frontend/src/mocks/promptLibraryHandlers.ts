import { http, HttpResponse } from 'msw'
import type {
  PromptLibraryRequest,
  PromptLibraryResponse,
  PromptLibraryUpdateRequest,
  PromptRequest,
  PromptResponse,
} from '../types/api'
import { mockGroups } from './fixtures'
import { mockPromptLibraries, mockPrompts } from './promptLibraryFixtures'

const ROLE_ORDER = ['VIEWER', 'EDITOR', 'MANAGER', 'OWNER'] as const

function holds(library: PromptLibraryResponse, minimum: (typeof ROLE_ORDER)[number]): boolean {
  return ROLE_ORDER.indexOf(library.myRole) >= ROLE_ORDER.indexOf(minimum)
}

function notFound() {
  return HttpResponse.json({ error: 'Prompt-Bibliothek nicht gefunden' }, { status: 404 })
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
    if (!/^[A-Za-z][A-Za-z0-9_]*$/.test(match[1])) {
      return `Ungültiger Platzhalter „{{${match[1]}}}“: Ein Variablenname beginnt mit einem Buchstaben und enthält nur Buchstaben, Ziffern und Unterstriche, ohne Leerzeichen.`
    }
    used.add(match[1])
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

/** The prompt library endpoints for the mocked frontend (#1901's API). */
export const promptLibraryHandlers = [
  http.get('/api/v1/prompt-libraries', () =>
    HttpResponse.json(
      Object.values(mockPromptLibraries).sort((a, b) => a.name.localeCompare(b.name)),
    ),
  ),

  http.post('/api/v1/prompt-libraries', async ({ request }) => {
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
    if (prompts.some((prompt) => prompt.name === body.name)) {
      return HttpResponse.json(
        { error: `Ein Prompt mit dem Namen „${body.name}“ gibt es in dieser Bibliothek bereits.` },
        { status: 409 },
      )
    }
    const now = new Date().toISOString()
    const prompt: PromptResponse = {
      id: `prompt-${crypto.randomUUID().slice(0, 8)}`,
      promptLibraryId: id,
      name: body.name,
      title: body.title,
      description: body.description ?? null,
      text: body.text,
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
    const updated: PromptResponse = {
      ...existing,
      name: body.name,
      title: body.title,
      description: body.description ?? null,
      text: body.text,
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
