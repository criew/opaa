import type { PromptLibraryResponse, PromptResponse } from '../types/api'

const INITIAL_PROMPT_LIBRARIES: Record<string, PromptLibraryResponse> = {
  'prompt-library-referat-50': {
    id: 'prompt-library-referat-50',
    name: 'Formulierungshilfen Referat 50',
    description: 'Anhörung, Vermerk und Ablehnung nach Hausstandard',
    ownerType: 'GROUP',
    ownerId: 'group-referat-50',
    ownerName: 'Referat 50',
    visibility: 'SHARED',
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
    visibility: 'ORGANIZATION',
    listed: true,
    myRole: 'VIEWER',
    promptCount: 1,
    succession: null,
    createdAt: '2026-09-01T10:00:00Z',
    updatedAt: '2026-09-02T10:00:00Z',
  },
}

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
  ],
}

// Mutable copies: the handlers read and write them, and the test setup resets them between tests.
export let mockPromptLibraries: Record<string, PromptLibraryResponse> =
  structuredClone(INITIAL_PROMPT_LIBRARIES)
export let mockPrompts: Record<string, PromptResponse[]> = structuredClone(INITIAL_PROMPTS)

export function resetMockPromptLibraries() {
  mockPromptLibraries = structuredClone(INITIAL_PROMPT_LIBRARIES)
  mockPrompts = structuredClone(INITIAL_PROMPTS)
}
