import { http, HttpResponse } from 'msw'
import type { AvailablePrompt, PromptForInsertion } from '../types/api'

const FORMULIERUNGSHILFEN = 'prompt-library-referat-50'
const HAUSWEIT = 'prompt-library-hausweit'

/** The space whose chat puts the Referat's prompt library first. */
const ASSOCIATED_SPACE = 'space-engineering'

/** The prompts of the mock, as GET .../prompts/{promptId} returns them. */
export const mockPromptsForInsertion: PromptForInsertion[] = [
  {
    id: 'prompt-zusammenfassung',
    promptLibraryId: FORMULIERUNGSHILFEN,
    name: 'zusammenfassung',
    title: 'Zusammenfassung',
    description: 'Stand eines Vorgangs zu einem Stichtag',
    text: 'Fasse den Stand des Vorgangs zum {{stichtag}} zusammen. Umfang: {{umfang}}.',
    variables: [
      { name: 'stichtag', label: 'Stichtag', type: 'DATE', required: true },
      {
        name: 'umfang',
        label: 'Umfang',
        type: 'SELECT',
        required: true,
        defaultValue: 'kurz',
        options: ['kurz', 'ausführlich'],
      },
    ],
    sortOrder: 0,
    createdAt: '2026-09-20T08:00:00Z',
    updatedAt: '2026-09-20T08:00:00Z',
  },
  {
    id: 'prompt-anhoerung',
    promptLibraryId: FORMULIERUNGSHILFEN,
    name: 'anhoerung',
    title: 'Anhörungsschreiben',
    description: 'Entwurf nach § 28 VwVfG',
    text:
      'Entwirf ein Anhörungsschreiben zum Aktenzeichen {{aktenzeichen}}, Stand {{CURRENT_DATE}}.' +
      ' Sachverhalt: {{sachverhalt}}',
    variables: [
      { name: 'aktenzeichen', label: 'Aktenzeichen', type: 'TEXT', required: true },
      { name: 'sachverhalt', label: 'Sachverhalt', type: 'TEXTAREA', required: false },
    ],
    sortOrder: 1,
    createdAt: '2026-09-20T08:00:00Z',
    updatedAt: '2026-09-20T08:00:00Z',
  },
  {
    id: 'prompt-dank',
    promptLibraryId: HAUSWEIT,
    name: 'dank',
    title: 'Dankesschreiben',
    description: null,
    text: 'Formuliere ein kurzes, freundliches Dankesschreiben. Gezeichnet {{USER_NAME}}.',
    variables: [],
    sortOrder: 0,
    createdAt: '2026-09-20T08:00:00Z',
    updatedAt: '2026-09-20T08:00:00Z',
  },
]

const LIBRARY_NAMES: Record<string, string> = {
  [FORMULIERUNGSHILFEN]: 'Formulierungshilfen Referat 50',
  [HAUSWEIT]: 'Hausweite Vorlagen',
}

/** Mirrors GET /api/v1/prompts/available: space-associated libraries first, then by name. */
export function mockAvailablePrompts(spaceId: string | null): AvailablePrompt[] {
  const entries = mockPromptsForInsertion.map((prompt): AvailablePrompt => ({
    id: prompt.id,
    libraryId: prompt.promptLibraryId,
    libraryName: LIBRARY_NAMES[prompt.promptLibraryId],
    name: prompt.name,
    title: prompt.title,
    description: prompt.description,
    hasVariables: prompt.variables.length > 0,
    associatedWithSpace:
      spaceId === ASSOCIATED_SPACE && prompt.promptLibraryId === FORMULIERUNGSHILFEN,
  }))
  return entries.sort(
    (a, b) =>
      Number(b.associatedWithSpace) - Number(a.associatedWithSpace) ||
      a.libraryName.localeCompare(b.libraryName, 'de'),
  )
}

export const promptChatHandlers = [
  http.get('/api/v1/prompts/available', ({ request }) => {
    const spaceId = new URL(request.url).searchParams.get('spaceId')
    return HttpResponse.json(mockAvailablePrompts(spaceId))
  }),

  http.get('/api/v1/prompt-libraries/:libraryId/prompts/:promptId', ({ params }) => {
    const prompt = mockPromptsForInsertion.find(
      (candidate) =>
        candidate.id === params.promptId && candidate.promptLibraryId === params.libraryId,
    )
    return prompt
      ? HttpResponse.json(prompt)
      : HttpResponse.json({ error: 'Prompt nicht gefunden' }, { status: 404 })
  }),
]
