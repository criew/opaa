import type { PromptLibraryResponse, PromptResponse } from '../types/api'

const INITIAL_PROMPT_LIBRARIES: Record<string, PromptLibraryResponse> = {
  'prompt-library-referat-50': {
    id: 'prompt-library-referat-50',
    name: 'Formulierungshilfen Referat 50',
    description: 'Anhörung, Vermerk und Ablehnung nach Hausstandard',
    ownerType: 'GROUP',
    ownerId: 'group-referat-50',
    ownerName: 'Referat 50',
    reach: { allAccounts: false, groupCount: 0, userCount: 1 },
    listed: false,
    myRole: 'MANAGER',
    promptCount: 2,
    succession: null,
    createdAt: '2026-09-01T10:00:00Z',
    updatedAt: '2026-09-20T10:00:00Z',
  },
  'prompt-library-organisation': {
    id: 'prompt-library-organisation',
    name: 'Hausweite Vorlagen',
    description: 'Freigegeben für die ganze Organisation',
    ownerType: 'GROUP',
    ownerId: 'group-zentrale',
    ownerName: 'Zentrale Dienste',
    reach: { allAccounts: true, groupCount: 0, userCount: 1 },
    listed: true,
    myRole: 'VIEWER',
    promptCount: 2,
    succession: null,
    createdAt: '2026-09-01T10:00:00Z',
    updatedAt: '2026-09-02T10:00:00Z',
  },
}

/**
 * A library the mock user administers without reading it (system administration without a right
 * of the formula): not in the list, reachable by its address, its prompts answer 403.
 */
const ADMINISTERED_ONLY: PromptLibraryResponse = {
  id: 'prompt-library-verwaltet',
  name: 'Vorlagen Personalrat',
  description: 'Nur verwaltet, nicht lesbar',
  ownerType: 'GROUP',
  ownerId: 'group-personalrat',
  ownerName: null,
  reach: { allAccounts: false, groupCount: 0, userCount: 1 },
  listed: false,
  myRole: 'OWNER',
  promptCount: 3,
  succession: null,
  createdAt: '2026-09-01T10:00:00Z',
  updatedAt: '2026-09-01T10:00:00Z',
}

export const mockUnreadablePromptLibraryIds: ReadonlySet<string> = new Set([ADMINISTERED_ONLY.id])

const INITIAL_PROMPTS: Record<string, PromptResponse[]> = {
  'prompt-library-referat-50': [
    {
      id: 'prompt-anhoerung',
      promptLibraryId: 'prompt-library-referat-50',
      name: 'anhoerung',
      title: 'Anhörungsschreiben',
      description: 'Anhörung nach § 28 VwVfG',
      text: 'Entwirf ein Anhörungsschreiben zum Aktenzeichen {{aktenzeichen}}, Stand {{CURRENT_DATE}}. Frist: {{frist}}.',
      variables: [
        { name: 'aktenzeichen', label: 'Aktenzeichen', type: 'TEXT', required: true },
        {
          name: 'frist',
          label: 'Frist',
          type: 'DATE',
          required: true,
          defaultValue: null,
          options: null,
        },
      ],
      sortOrder: 0,
      createdAt: '2026-09-01T10:00:00Z',
      updatedAt: '2026-09-01T10:00:00Z',
    },
    {
      id: 'prompt-vermerk',
      promptLibraryId: 'prompt-library-referat-50',
      name: 'vermerk',
      title: 'Vermerk',
      description: null,
      text: 'Fasse den Sachverhalt als Vermerk für {{USER_NAME}} zusammen.',
      variables: [],
      sortOrder: 1,
      createdAt: '2026-09-01T10:00:00Z',
      updatedAt: '2026-09-01T10:00:00Z',
    },
  ],
  'prompt-library-organisation': [
    {
      id: 'prompt-ablehnung',
      promptLibraryId: 'prompt-library-organisation',
      name: 'ablehnung',
      title: 'Ablehnungsbescheid',
      description: null,
      text: 'Formuliere eine Ablehnung im Ton {{ton}}.',
      variables: [
        {
          name: 'ton',
          label: 'Ton',
          type: 'SELECT',
          required: false,
          defaultValue: 'sachlich',
          options: ['sachlich', 'freundlich'],
        },
      ],
      sortOrder: 0,
      createdAt: '2026-09-01T10:00:00Z',
      updatedAt: '2026-09-01T10:00:00Z',
    },
    {
      id: 'prompt-zusammenfassung',
      promptLibraryId: 'prompt-library-organisation',
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
      sortOrder: 1,
      createdAt: '2026-09-01T10:00:00Z',
      updatedAt: '2026-09-01T10:00:00Z',
    },
  ],
}

/** The space whose chat puts the Referat's prompt library first (a mock space association). */
export const MOCK_PROMPT_SPACE_ID = 'space-engineering'
export const MOCK_PROMPT_SPACE_LIBRARY_ID = 'prompt-library-referat-50'

// Mutable copies: the handlers read and write them, and the test setup resets them between tests.
export let mockPromptLibraries: Record<string, PromptLibraryResponse> = structuredClone({
  ...INITIAL_PROMPT_LIBRARIES,
  [ADMINISTERED_ONLY.id]: ADMINISTERED_ONLY,
})
export let mockPrompts: Record<string, PromptResponse[]> = structuredClone(INITIAL_PROMPTS)

export function resetMockPromptLibraries() {
  mockPromptLibraries = structuredClone({
    ...INITIAL_PROMPT_LIBRARIES,
    [ADMINISTERED_ONLY.id]: ADMINISTERED_ONLY,
  })
  mockPrompts = structuredClone(INITIAL_PROMPTS)
}
