import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import DocumentMetadataPanel from './DocumentMetadataPanel'
import type { DocumentMetadataFieldResponse, DocumentMetadataResponse } from '../../types/api'

const {
  mockGetDocumentMetadata,
  mockSetDocumentMetadataValue,
  mockDeleteDocumentMetadataValue,
  mockGetDocumentTypeVocabulary,
} = vi.hoisted(() => ({
  mockGetDocumentMetadata: vi.fn(),
  mockSetDocumentMetadataValue: vi.fn(),
  mockDeleteDocumentMetadataValue: vi.fn(async () => undefined),
  mockGetDocumentTypeVocabulary: vi.fn(async () => ({
    items: [
      { code: 'DIENSTANWEISUNG', label: 'Dienstanweisung' },
      { code: 'VERMERK', label: 'Vermerk' },
    ],
  })),
}))

vi.mock('../../services/api', async () => {
  const actual = await vi.importActual<typeof import('../../services/api')>('../../services/api')
  return {
    ...actual,
    getDocumentMetadata: mockGetDocumentMetadata,
    setDocumentMetadataValue: mockSetDocumentMetadataValue,
    deleteDocumentMetadataValue: mockDeleteDocumentMetadataValue,
    getDocumentTypeVocabulary: mockGetDocumentTypeVocabulary,
  }
})

const fields: DocumentMetadataFieldResponse[] = [
  {
    fieldKey: 'title',
    label: 'Titel',
    value: 'Dienstanweisung zur IT-Nutzung',
    displayValue: 'Dienstanweisung zur IT-Nutzung',
    origin: 'DETERMINISTIC',
    extractionVersion: 1,
  },
  {
    fieldKey: 'document_type',
    label: 'Dokumentart',
    value: 'DIENSTANWEISUNG',
    displayValue: 'Dienstanweisung',
    origin: 'DERIVED',
    confidence: 0.82,
    modelId: 'mock-model',
  },
  { fieldKey: 'document_date', label: 'Datum/Stand' },
]

function metadataOf(items: DocumentMetadataFieldResponse[]): DocumentMetadataResponse {
  return { documentId: 'doc-1', fields: items }
}

describe('DocumentMetadataPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetDocumentMetadata.mockResolvedValue(metadataOf(fields))
  })

  it('shows every core field with its value, marks a derived value and names an empty field', async () => {
    renderWithProviders(
      <DocumentMetadataPanel
        libraryId="library-team"
        documentId="doc-1"
        fileName="dienstanweisung.pdf"
        canEdit={false}
      />,
    )

    const region = await screen.findByRole('region', { name: 'Metadaten von dienstanweisung.pdf' })
    expect(within(region).getByText('Dienstanweisung zur IT-Nutzung')).toBeInTheDocument()
    expect(within(region).getByText('automatisch ermittelt')).toBeInTheDocument()
    expect(within(region).getByText('Dienstanweisung')).toBeInTheDocument()
    expect(within(region).getByText('abgeleitet')).toBeInTheDocument()
    expect(within(region).getByText('– (leer)')).toBeInTheDocument()
    expect(mockGetDocumentMetadata).toHaveBeenCalledWith('library-team', 'doc-1')
    // A reader sees the values but no correction controls.
    expect(within(region).queryByRole('button', { name: /bearbeiten/ })).not.toBeInTheDocument()
    expect(within(region).queryByRole('button', { name: /löschen/ })).not.toBeInTheDocument()
  })

  it('lets an editor set the Dokumentart from the vocabulary and shows the manual value', async () => {
    mockSetDocumentMetadataValue.mockResolvedValue({
      fieldKey: 'document_type',
      label: 'Dokumentart',
      value: 'VERMERK',
      displayValue: 'Vermerk',
      origin: 'MANUAL',
      actorDisplayName: 'Erika Muster',
      updatedAt: '2026-09-04T10:00:00Z',
    } satisfies DocumentMetadataFieldResponse)
    renderWithProviders(
      <DocumentMetadataPanel
        libraryId="library-team"
        documentId="doc-1"
        fileName="dienstanweisung.pdf"
        canEdit
      />,
    )
    const user = userEvent.setup()

    await user.click(
      await screen.findByRole('button', { name: 'Dokumentart von dienstanweisung.pdf bearbeiten' }),
    )
    const dialog = await screen.findByRole('dialog', { name: 'Dokumentart ändern' })
    await user.click(await within(dialog).findByRole('combobox', { name: /Dokumentart/ }))
    await user.click(await screen.findByRole('option', { name: 'Vermerk' }))
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    await waitFor(() =>
      expect(mockSetDocumentMetadataValue).toHaveBeenCalledWith(
        'library-team',
        'doc-1',
        'document_type',
        { vocabularyCode: 'VERMERK' },
      ),
    )
    const region = screen.getByRole('region', { name: 'Metadaten von dienstanweisung.pdf' })
    expect(await within(region).findByText('Vermerk')).toBeInTheDocument()
    expect(within(region).getByText('manuell')).toBeInTheDocument()
    expect(within(region).queryByText('abgeleitet')).not.toBeInTheDocument()
  })

  it('deletes a value after confirmation and shows the field as empty', async () => {
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderWithProviders(
      <DocumentMetadataPanel
        libraryId="library-team"
        documentId="doc-1"
        fileName="dienstanweisung.pdf"
        canEdit
      />,
    )
    const user = userEvent.setup()

    await user.click(
      await screen.findByRole('button', { name: 'Titel von dienstanweisung.pdf löschen' }),
    )

    await waitFor(() =>
      expect(mockDeleteDocumentMetadataValue).toHaveBeenCalledWith(
        'library-team',
        'doc-1',
        'title',
      ),
    )
    const region = screen.getByRole('region', { name: 'Metadaten von dienstanweisung.pdf' })
    expect(within(region).queryByText('Dienstanweisung zur IT-Nutzung')).not.toBeInTheDocument()
    expect(within(region).getAllByText('– (leer)')).toHaveLength(2)
    // An empty field can be set but has nothing to delete.
    expect(
      screen.queryByRole('button', { name: 'Titel von dienstanweisung.pdf löschen' }),
    ).not.toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Titel von dienstanweisung.pdf bearbeiten' }),
    ).toBeInTheDocument()
  })

  it('surfaces a rejected value from the backend inside the dialog', async () => {
    mockSetDocumentMetadataValue.mockRejectedValue(new Error('Der Titel darf nicht leer sein'))
    renderWithProviders(
      <DocumentMetadataPanel
        libraryId="library-team"
        documentId="doc-1"
        fileName="dienstanweisung.pdf"
        canEdit
      />,
    )
    const user = userEvent.setup()

    await user.click(
      await screen.findByRole('button', { name: 'Titel von dienstanweisung.pdf bearbeiten' }),
    )
    const dialog = await screen.findByRole('dialog', { name: 'Titel ändern' })
    const input = within(dialog).getByRole('textbox', { name: /Titel/ })
    await user.clear(input)
    await user.type(input, 'Neu')
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    expect(await within(dialog).findByText('Der Titel darf nicht leer sein')).toBeInTheDocument()
  })
})
