import type { DocumentTemplateResponse, DocumentTemplateSettingsResponse } from '@/shared/api/types'

function initialTemplates(): Record<string, DocumentTemplateResponse> {
  return {
    'document-template-vermerk': {
      id: 'document-template-vermerk',
      name: 'Vermerk Referat 50',
      description: 'Hausvorlage für Vermerke mit Bezug, Sachstand und Maßnahmen.',
      ownerType: 'USER',
      ownerId: 'mock-user-id',
      ownerName: 'Max Mustermann',
      reach: { allAccounts: false, groupCount: 1, userCount: 0 },
      myRole: 'OWNER',
      responsibility: { function: 'Referat 50 – Organisation', missing: false },
      fileName: 'vermerk-referat-50.docx',
      sizeBytes: 48_213,
      revision: 2,
      placeholders: [
        { name: 'aktenzeichen', kind: 'FIELD', fields: [] },
        { name: 'betreff', kind: 'FIELD', fields: [] },
        { name: 'massnahmen', kind: 'LIST', fields: ['titel', 'frist'] },
        { name: 'vertraulich', kind: 'CONDITION', fields: [] },
      ],
      succession: null,
      createdAt: '2026-11-09T08:12:00Z',
      updatedAt: '2026-11-10T07:41:00Z',
    },
  }
}

function initialSettings(): DocumentTemplateSettingsResponse {
  return { enabled: false, updatedAt: null, updatedBy: null }
}

/**
 * The templates and the switch „Vorlagen" (off as delivered) of the mock installation, mutable so
 * a change shows on the next GET.
 */
export const mockDocumentTemplateState = {
  templates: initialTemplates(),
  settings: initialSettings(),
}

export function resetDocumentTemplateMockState() {
  mockDocumentTemplateState.templates = initialTemplates()
  mockDocumentTemplateState.settings = initialSettings()
}
