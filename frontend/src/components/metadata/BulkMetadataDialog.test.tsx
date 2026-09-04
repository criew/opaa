import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import BulkMetadataDialog from './BulkMetadataDialog'
import type { BulkMetadataValueResponse } from '../../types/api'

const { mockBulkSetDocumentMetadata, mockGetDocumentTypeVocabulary } = vi.hoisted(() => ({
  mockBulkSetDocumentMetadata: vi.fn(),
  mockGetDocumentTypeVocabulary: vi.fn(async () => ({
    items: [
      { code: 'SATZUNG_ORDNUNG', label: 'Satzung/Ordnung' },
      { code: 'VERMERK', label: 'Vermerk' },
    ],
  })),
}))

vi.mock('../../services/api', async () => {
  const actual = await vi.importActual<typeof import('../../services/api')>('../../services/api')
  return {
    ...actual,
    bulkSetDocumentMetadata: mockBulkSetDocumentMetadata,
    getDocumentTypeVocabulary: mockGetDocumentTypeVocabulary,
  }
})

describe('BulkMetadataDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('asks for one field and one value, confirms with the count and reports the outcome', async () => {
    const result: BulkMetadataValueResponse = {
      updatedCount: 2,
      unchangedCount: 1,
      rejectedDocumentIds: ['doc-gone'],
      correlationRef: 'metadata-bulk-1',
    }
    mockBulkSetDocumentMetadata.mockResolvedValue(result)
    const onDone = vi.fn()
    renderWithProviders(
      <BulkMetadataDialog
        open
        onClose={vi.fn()}
        libraryId="library-team"
        documentIds={['doc-1', 'doc-2', 'doc-3', 'doc-gone']}
        onDone={onDone}
      />,
    )
    const user = userEvent.setup()

    const dialog = await screen.findByRole('dialog', { name: 'Feld für 4 Dokumente setzen' })
    // Nothing chosen yet: the next step stays closed.
    expect(within(dialog).getByRole('button', { name: 'Weiter' })).toBeDisabled()
    await user.click(await within(dialog).findByRole('combobox', { name: /Dokumentart/ }))
    await user.click(await screen.findByRole('option', { name: 'Satzung/Ordnung' }))
    await user.click(within(dialog).getByRole('button', { name: 'Weiter' }))

    expect(
      within(dialog).getByText(/Dokumentart = „Satzung\/Ordnung" für 4 Dokumente setzen\?/),
    ).toBeInTheDocument()
    expect(mockBulkSetDocumentMetadata).not.toHaveBeenCalled()
    await user.click(within(dialog).getByRole('button', { name: 'Zuweisen' }))

    await waitFor(() =>
      expect(mockBulkSetDocumentMetadata).toHaveBeenCalledWith('library-team', {
        fieldKey: 'document_type',
        value: { vocabularyCode: 'SATZUNG_ORDNUNG' },
        documentIds: ['doc-1', 'doc-2', 'doc-3', 'doc-gone'],
      }),
    )
    expect(await within(dialog).findByText(/2 Dokumente aktualisiert, 1 unverändert/)).toBeVisible()
    expect(within(dialog).getByText(/1 Dokument wurde abgewiesen/)).toBeVisible()
    expect(onDone).toHaveBeenCalledWith(result)
  })

  it('switches the value input with the chosen field and sends a date with its precision', async () => {
    mockBulkSetDocumentMetadata.mockResolvedValue({
      updatedCount: 1,
      unchangedCount: 0,
      rejectedDocumentIds: [],
      correlationRef: 'metadata-bulk-2',
    })
    renderWithProviders(
      <BulkMetadataDialog
        open
        onClose={vi.fn()}
        libraryId="library-team"
        documentIds={['doc-1']}
        onDone={vi.fn()}
      />,
    )
    const user = userEvent.setup()

    const dialog = await screen.findByRole('dialog', { name: 'Feld für 1 Dokument setzen' })
    await user.click(within(dialog).getByRole('combobox', { name: /^Feld$/ }))
    await user.click(await screen.findByRole('option', { name: 'Datum/Stand' }))
    await user.type(within(dialog).getByLabelText(/^Datum/), '2024-05-17')
    await user.click(within(dialog).getByRole('combobox', { name: /Genauigkeit/ }))
    await user.click(await screen.findByRole('option', { name: 'Jahr' }))
    await user.click(within(dialog).getByRole('button', { name: 'Weiter' }))
    await user.click(within(dialog).getByRole('button', { name: 'Zuweisen' }))

    await waitFor(() =>
      expect(mockBulkSetDocumentMetadata).toHaveBeenCalledWith('library-team', {
        fieldKey: 'document_date',
        value: { dateValue: '2024-05-17', datePrecision: 'YEAR' },
        documentIds: ['doc-1'],
      }),
    )
  })

  it('shows a backend rejection and returns to the form', async () => {
    mockBulkSetDocumentMetadata.mockRejectedValue(new Error('Kein Zugriff auf diese Bibliothek'))
    renderWithProviders(
      <BulkMetadataDialog
        open
        onClose={vi.fn()}
        libraryId="library-team"
        documentIds={['doc-1']}
        onDone={vi.fn()}
      />,
    )
    const user = userEvent.setup()

    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('combobox', { name: /^Feld$/ }))
    await user.click(await screen.findByRole('option', { name: 'Titel' }))
    await user.type(within(dialog).getByRole('textbox', { name: /Titel/ }), 'Neuer Titel')
    await user.click(within(dialog).getByRole('button', { name: 'Weiter' }))
    await user.click(within(dialog).getByRole('button', { name: 'Zuweisen' }))

    expect(await within(dialog).findByText('Kein Zugriff auf diese Bibliothek')).toBeVisible()
    expect(within(dialog).getByRole('button', { name: 'Weiter' })).toBeInTheDocument()
  })

  it('assigns "kein Wert ermittelbar" to the whole selection through the same steps', async () => {
    mockBulkSetDocumentMetadata.mockResolvedValue({
      updatedCount: 3,
      unchangedCount: 0,
      rejectedDocumentIds: [],
      correlationRef: 'metadata-bulk-3',
    })
    renderWithProviders(
      <BulkMetadataDialog
        open
        onClose={vi.fn()}
        libraryId="library-team"
        documentIds={['doc-1', 'doc-2', 'doc-3']}
        onDone={vi.fn()}
      />,
    )
    const user = userEvent.setup()

    const dialog = await screen.findByRole('dialog', { name: 'Feld für 3 Dokumente setzen' })
    await user.click(within(dialog).getByRole('combobox', { name: /^Feld$/ }))
    await user.click(await screen.findByRole('option', { name: 'Datum/Stand' }))
    await user.click(within(dialog).getByRole('checkbox', { name: 'Kein Wert ermittelbar' }))
    await user.click(within(dialog).getByRole('button', { name: 'Weiter' }))
    expect(
      within(dialog).getByText(/Datum\/Stand = „kein Wert ermittelbar" für 3 Dokumente setzen\?/),
    ).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Zuweisen' }))

    await waitFor(() =>
      expect(mockBulkSetDocumentMetadata).toHaveBeenCalledWith('library-team', {
        fieldKey: 'document_date',
        value: { state: 'NOT_DETERMINABLE' },
        documentIds: ['doc-1', 'doc-2', 'doc-3'],
      }),
    )
  })
})
