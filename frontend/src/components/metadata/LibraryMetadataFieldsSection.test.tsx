import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import LibraryMetadataFieldsSection from './LibraryMetadataFieldsSection'

const {
  mockList,
  mockCreate,
  mockDelete,
  mockFieldUsage,
  mockValueUsage,
  mockRemap,
  mockUpdate,
  mockRelabel,
  mockImpact,
  mockUpdateCorePrefix,
} = vi.hoisted(() => ({
  mockList: vi.fn(),
  mockCreate: vi.fn(),
  mockDelete: vi.fn(),
  mockFieldUsage: vi.fn(),
  mockValueUsage: vi.fn(),
  mockRemap: vi.fn(),
  mockUpdate: vi.fn(),
  mockRelabel: vi.fn(),
  mockImpact: vi.fn(),
  mockUpdateCorePrefix: vi.fn(),
}))

vi.mock('../../services/api', async () => {
  const actual = await vi.importActual<typeof import('../../services/api')>('../../services/api')
  return {
    ...actual,
    listLibraryMetadataFields: mockList,
    createLibraryMetadataField: mockCreate,
    deleteLibraryMetadataField: mockDelete,
    getLibraryMetadataFieldUsage: mockFieldUsage,
    getLibraryMetadataFieldValueUsage: mockValueUsage,
    remapLibraryMetadataFieldValue: mockRemap,
    updateLibraryMetadataField: mockUpdate,
    relabelLibraryMetadataFieldValue: mockRelabel,
    getMetadataChangeImpact: mockImpact,
    updateCoreContextPrefix: mockUpdateCorePrefix,
  }
})

const fassung = {
  fieldKey: 'fassung',
  documentFieldKey: 'lib:fassung',
  label: 'Fassung',
  type: 'SELECT' as const,
  valuePattern: null,
  filter: true,
  contextPrefix: false,
  citationPosition: 1,
  sortOrder: 10,
  values: [
    { code: 'F2026', label: 'Fassung 2026' },
    { code: 'F2027', label: 'Fassung 2027' },
  ],
}

describe('LibraryMetadataFieldsSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockList.mockResolvedValue({
      items: [fassung],
      coreContextPrefix: { title: true, documentType: false, documentDate: false },
      documentsAwaitingContextPrefixRerun: 0,
    })
    mockImpact.mockResolvedValue({
      affectedDocuments: 12,
      affectedChunks: 4812,
      embeddingCalls: 4812,
      estimatedSeconds: 2400,
      reembeddingRequired: true,
      rateSource: 'MEASURED',
    })
    mockUpdateCorePrefix.mockResolvedValue({
      title: true,
      documentType: true,
      documentDate: false,
    })
    mockFieldUsage.mockResolvedValue({ documentCount: 4 })
    mockValueUsage.mockResolvedValue({ documentCount: 3 })
    mockRemap.mockResolvedValue({
      remappedDocuments: 3,
      clearedDocuments: 0,
      correlationRef: 'metadata-remap-1',
    })
  })

  it('shows a field with its Wirkstellen, its value list and the warning about value lists', async () => {
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />)

    expect(await screen.findByText('Fassung')).toBeInTheDocument()
    expect(screen.getByText('Filter')).toBeInTheDocument()
    expect(screen.getByText('Beleg 1')).toBeInTheDocument()
    expect(screen.getByText('Fassung 2026 (F2026)')).toBeInTheDocument()
    expect(screen.getByText(/keine schutzbedürftigen Bezeichnungen tragen/)).toBeInTheDocument()
  })

  it('never offers a field without a retrieval effect', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />)

    await user.click(await screen.findByRole('button', { name: 'Feld anlegen' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('checkbox', { name: 'Wirkt im Filter' }))

    expect(within(dialog).getByText(/genügt nicht/)).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Anlegen' })).toBeDisabled()
    expect(mockCreate).not.toHaveBeenCalled()
  })

  it('states the number of affected documents before a value mapping is confirmed', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />)

    await screen.findByText('Fassung 2026 (F2026)')
    await user.click(screen.getByRole('button', { name: 'Wert Fassung 2026 bearbeiten' }))

    expect(await screen.findByText(/3 Dokument\(e\) tragen „F2026“/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Abbildung bestätigen' }))
    expect(mockRemap).toHaveBeenCalledWith('library-team', 'fassung', 'F2026', null)
  })

  it('shows the Folgekosten of a field deletion before it is confirmed', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />)

    await user.click(await screen.findByRole('button', { name: 'Feld Fassung löschen' }))

    expect(
      await screen.findByText(/4 Dokument\(e\) tragen einen Wert für „Fassung“/),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Endgültig löschen' }))
    expect(mockDelete).toHaveBeenCalledWith('library-team', 'fassung')
  })

  it('names the Folgekosten of a prefix-effective change before it is saved', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />)

    await user.click(await screen.findByRole('button', { name: 'Feld Fassung bearbeiten' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('checkbox', { name: 'Wirkt im Kontextpräfix' }))

    // Concrete numbers, not a general warning - the whole point of the Kostenanzeige.
    expect(
      await within(dialog).findByText(/4812 Abschnitte in 12 Dokument\(en\) neu/),
    ).toBeInTheDocument()
    expect(within(dialog).getByText(/rund 40 Minuten/)).toBeInTheDocument()
    expect(within(dialog).getByText(/Speichern setzt nichts in Bewegung/)).toBeInTheDocument()
    expect(mockImpact).toHaveBeenCalledWith(
      'library-team',
      'fassung',
      'CONTEXT_PREFIX_ENABLED',
      undefined,
    )
  })

  it('switches a core field into the Kontextpräfix only after its Folgekosten were shown', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />)

    await user.click(await screen.findByRole('switch', { name: 'Dokumentart' }))
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByText(/4812 Abschnitte/)).toBeInTheDocument()
    expect(mockUpdateCorePrefix).not.toHaveBeenCalled()

    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    expect(mockUpdateCorePrefix).toHaveBeenCalledWith('library-team', {
      documentType: true,
      documentDate: false,
    })
  })

  it('names a change without Folgekosten as free instead of warning about it', async () => {
    mockImpact.mockResolvedValue({
      affectedDocuments: 0,
      affectedChunks: 0,
      embeddingCalls: 0,
      estimatedSeconds: 0,
      reembeddingRequired: false,
      rateSource: 'CONFIGURED',
    })
    const user = userEvent.setup()
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />)

    await user.click(await screen.findByRole('button', { name: 'Feld Fassung löschen' }))
    const dialog = await screen.findByRole('dialog')

    expect(
      await within(dialog).findByText('Diese Änderung hat keine Folgekosten.'),
    ).toBeInTheDocument()
  })

  it('points a Fachperson at the administration page when documents wait for re-embedding', async () => {
    mockList.mockResolvedValue({
      items: [fassung],
      coreContextPrefix: { title: true, documentType: true, documentDate: false },
      documentsAwaitingContextPrefixRerun: 7,
    })
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />, {
      withRouter: true,
    })

    expect(await screen.findByText(/7 Dokument\(e\) warten auf Neu-Einbetten/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Suche & Indexierung/ })).toHaveAttribute(
      'href',
      '/admin/search',
    )
  })

  it('offers no schema change without the management right', async () => {
    renderWithProviders(
      <LibraryMetadataFieldsSection libraryId="library-team" canManageSchema={false} />,
    )

    await screen.findByText('Fassung')
    expect(screen.queryByRole('button', { name: 'Feld anlegen' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Feld Fassung löschen' })).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Feld Fassung bearbeiten' }),
    ).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Wert ergänzen' })).not.toBeInTheDocument()
  })

  it('changes label, Wirkstellen and citation position of an existing field', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />)

    await user.click(await screen.findByRole('button', { name: 'Feld Fassung bearbeiten' }))
    const dialog = await screen.findByRole('dialog')
    await user.clear(within(dialog).getByLabelText('Feldname'))
    await user.type(within(dialog).getByLabelText('Feldname'), 'Fassung/Stand')
    await user.click(within(dialog).getByRole('checkbox', { name: 'Wirkt im Kontextpräfix' }))
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    expect(mockUpdate).toHaveBeenCalledWith('library-team', 'fassung', {
      label: 'Fassung/Stand',
      filter: true,
      contextPrefix: true,
      citationPosition: 1,
    })
  })

  it('corrects the label of a value without touching the documents that carry its code', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />)

    await screen.findByText('Fassung 2026 (F2026)')
    await user.click(screen.getByRole('button', { name: 'Wert Fassung 2026 bearbeiten' }))
    const label = await screen.findByLabelText('Bezeichnung')
    await user.clear(label)
    await user.type(label, 'Fassung 2026 (neu)')
    await user.click(screen.getByRole('button', { name: 'Bezeichnung speichern' }))

    expect(mockRelabel).toHaveBeenCalledWith(
      'library-team',
      'fassung',
      'F2026',
      'Fassung 2026 (neu)',
    )
    expect(mockRemap).not.toHaveBeenCalled()
  })
})
