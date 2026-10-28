import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../../../test/test-utils'
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
  mockRun,
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
  mockRun: vi.fn(),
}))

vi.mock('../../api/libraryMetadataApi', async () => {
  const actual = await vi.importActual<typeof import('../../api/libraryMetadataApi')>(
    '../../api/libraryMetadataApi',
  )
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
    runLibraryMetadataSchemaChanges: mockRun,
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
  sortOrder: 10,
  deletionPending: false,
  values: [
    { code: 'F2026', label: 'Fassung 2026', retiring: false },
    { code: 'F2027', label: 'Fassung 2027', retiring: false },
  ],
}

/** The same library in the middle of the follow-up run: F2026 is retired, not removed. */
const fassungRetiring = {
  ...fassung,
  values: [
    { code: 'F2026', label: 'Fassung 2026', retiring: true, remapTargetCode: 'F2027' },
    { code: 'F2027', label: 'Fassung 2027', retiring: false },
  ],
}

/** The value list of a selection field opens from its count, e.g. „2 Werte“. */
async function openValues(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: /Werte\s*von Fassung/ }))
}

/** Changing and deleting a field start from its row's „Ändern“. */
const EDIT_FIELD = { name: /^Ändern\s*Feld Fassung$/ }

const runningRemap = {
  kind: 'VALUE_REMAP' as const,
  fieldKey: 'fassung',
  valueCode: 'F2026',
  targetCode: 'F2027',
  processedDocuments: 1,
  remainingDocuments: 2,
  correlationRef: 'metadata-remap-1',
}

describe('LibraryMetadataFieldsSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockList.mockResolvedValue({
      items: [fassung],
      coreContextPrefix: { title: true, documentType: false, documentDate: false },
      documentsAwaitingContextPrefixRerun: 0,
      pendingSchemaChanges: [],
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
      remainingDocuments: 0,
      complete: true,
      correlationRef: 'metadata-remap-1',
    })
    mockDelete.mockResolvedValue(null)
    mockRun.mockResolvedValue({
      processedDocuments: 2,
      skippedDocuments: 0,
      remainingDocuments: 0,
      complete: true,
      pendingChanges: [],
    })
  })

  it('shows a field with its Wirkstellen, its value list and the warning about value lists', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />)

    const row = (await screen.findByText('Fassung')).closest('li')!
    expect(row).toHaveTextContent(/Auswahl · 2 Werte.* · Filter/)
    await openValues(user)
    expect(within(row).getByText('Fassung 2026')).toBeInTheDocument()
    expect(within(row).getByText('Fassung 2027')).toBeInTheDocument()
    expect(
      screen.getByText('Wertelisten sieht jede Person, die die Bibliothek nutzt.'),
    ).toBeInTheDocument()
  })

  it('never offers a field without a retrieval effect', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />)

    await user.click(await screen.findByRole('button', { name: 'Feld anlegen' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('checkbox', { name: 'Wirkt im Filter' }))

    expect(
      within(dialog).getByText(/mindestens im Filter oder im Kontextpräfix wirken/),
    ).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Anlegen' })).toBeDisabled()
    expect(mockCreate).not.toHaveBeenCalled()
  })

  it('states the number of affected documents before a value mapping is confirmed', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />)

    await openValues(user)
    await user.click(screen.getByRole('button', { name: 'Wert Fassung 2026 bearbeiten' }))

    expect(await screen.findByText(/3 Dokument\(e\) tragen „F2026“/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Abbildung bestätigen' }))
    expect(mockRemap).toHaveBeenCalledWith('library-team', 'fassung', 'F2026', null)
  })

  it('shows the Folgekosten of a field deletion before it is confirmed', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />)

    await user.click(await screen.findByRole('button', EDIT_FIELD))
    await user.click(await screen.findByRole('button', { name: 'Feld löschen' }))

    expect(
      await screen.findByText(/4 Dokument\(e\) tragen einen Wert für „Fassung“/),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Endgültig löschen' }))
    expect(mockDelete).toHaveBeenCalledWith('library-team', 'fassung')
  })

  it('names the Folgekosten of a prefix-effective change before it is saved', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />)

    await user.click(await screen.findByRole('button', EDIT_FIELD))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('checkbox', { name: 'Wirkt im Kontextpräfix' }))

    // Concrete numbers, not a general warning - the whole point of the Kostenanzeige.
    expect(await within(dialog).findByText(/4812 Abschnitte in/)).toBeInTheDocument()
    expect(within(dialog).getByText(/rund 40 Minuten/)).toBeInTheDocument()
    expect(within(dialog).getByText(/Speichern setzt nichts in Bewegung/)).toBeInTheDocument()
    expect(mockImpact).toHaveBeenCalledWith(
      'library-team',
      'fassung',
      'CONTEXT_PREFIX_ENABLED',
      undefined,
    )
  })

  it('names the Folgekosten of a newly defined prefix-effective field', async () => {
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

    await user.click(await screen.findByRole('button', { name: 'Feld anlegen' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('checkbox', { name: 'Wirkt im Kontextpräfix' }))

    // A field nobody has filled yet changes no document's prefix - and says so, instead of
    // leaving the most expensive row of the Kostentabelle without any number at all.
    expect(
      await within(dialog).findByText('Diese Änderung hat keine Folgekosten.'),
    ).toBeInTheDocument()
    expect(mockImpact).toHaveBeenCalledWith('library-team', '', 'FIELD_ADDED', undefined)
  })

  it('switches a core field into the Kontextpräfix only after its Folgekosten were shown', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />)

    expect(await screen.findByText('Titel')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Ändern\s*Kontextpräfix/ }))
    await user.click(screen.getByRole('switch', { name: 'Dokumentart' }))
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

    await user.click(await screen.findByRole('button', EDIT_FIELD))
    await user.click(await screen.findByRole('button', { name: 'Feld löschen' }))
    const confirm = await screen.findByRole('button', { name: 'Endgültig löschen' })
    const dialog = confirm.closest<HTMLElement>('[role="dialog"]')!

    expect(
      await within(dialog).findByText('Diese Änderung hat keine Folgekosten.'),
    ).toBeInTheDocument()
  })

  it('points a Fachperson at the administration page when documents wait for re-embedding', async () => {
    mockList.mockResolvedValue({
      items: [fassung],
      coreContextPrefix: { title: true, documentType: true, documentDate: false },
      documentsAwaitingContextPrefixRerun: 7,
      pendingSchemaChanges: [],
    })
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />, {
      withRouter: true,
    })

    expect(await screen.findByText(/7 Dokumente warten auf Neu-Einbetten/)).toBeInTheDocument()
    // Into the tab that carries the follow-up run - not onto the overview, from which one more
    // click would be needed.
    expect(screen.getByRole('link', { name: /Suche & Indexierung/ })).toHaveAttribute(
      'href',
      '/admin/search/index',
    )
  })

  it('offers no schema change without the management right', async () => {
    renderWithProviders(
      <LibraryMetadataFieldsSection libraryId="library-team" canManageSchema={false} />,
    )

    await screen.findByText('Fassung')
    expect(screen.queryByRole('button', { name: 'Feld anlegen' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', EDIT_FIELD)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Kontextpräfix/ })).not.toBeInTheDocument()
    await openValues(userEvent.setup())
    expect(screen.getByText('Fassung 2026')).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Wert Fassung 2026 bearbeiten' }),
    ).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Wert ergänzen' })).not.toBeInTheDocument()
  })

  it('changes label and Wirkstellen of an existing field', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />)

    await user.click(await screen.findByRole('button', EDIT_FIELD))
    const dialog = await screen.findByRole('dialog')
    await user.clear(within(dialog).getByLabelText('Feldname'))
    await user.type(within(dialog).getByLabelText('Feldname'), 'Fassung/Stand')
    await user.click(within(dialog).getByRole('checkbox', { name: 'Wirkt im Kontextpräfix' }))
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    expect(mockUpdate).toHaveBeenCalledWith('library-team', 'fassung', {
      label: 'Fassung/Stand',
      filter: true,
      contextPrefix: true,
    })
  })

  it('corrects the label of a value without touching the documents that carry its code', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />)

    await openValues(user)
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

describe('LibraryMetadataFieldsSection, laufende Schemaänderung (#1361)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockImpact.mockResolvedValue({
      affectedDocuments: 0,
      affectedChunks: 0,
      embeddingCalls: 0,
      estimatedSeconds: 0,
      reembeddingRequired: false,
      rateSource: 'CONFIGURED',
    })
    mockList.mockResolvedValue({
      items: [fassungRetiring],
      coreContextPrefix: { title: true, documentType: false, documentDate: false },
      documentsAwaitingContextPrefixRerun: 0,
      pendingSchemaChanges: [runningRemap],
    })
    mockRun.mockResolvedValue({
      processedDocuments: 2,
      skippedDocuments: 0,
      remainingDocuments: 0,
      complete: true,
      pendingChanges: [],
    })
  })

  /**
   * The mixed state must be visible - including the promise that the affected documents are found
   * until the end of the run. A retired value is therefore still listed, but
   * no longer editable.
   */
  it('zeigt die laufende Abbildung mit ihrem Fortschritt und legt den Wert still', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />)

    expect(
      await screen.findByText(
        /Wert „F2026“ des Feldes „Fassung“ wird auf „F2027“ abgebildet: 2 Dokumente offen/,
      ),
    ).toBeInTheDocument()
    expect(screen.getByText(/bleibt der alte Wert in der Werteliste/)).toBeInTheDocument()
    await openValues(user)
    expect(screen.getByText('Fassung 2026 · wird abgebildet')).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Wert Fassung 2026 bearbeiten' }),
    ).not.toBeInTheDocument()
  })

  it('setzt den Lauf auf Anforderung fort, bis nichts mehr offen ist', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />)

    await user.click(await screen.findByRole('button', { name: 'Fortsetzen' }))

    expect(mockRun).toHaveBeenCalledWith('library-team')
  })

  it('bietet an einem Feld, das gerade gelöscht wird, keine Schemaänderung mehr an', async () => {
    mockList.mockResolvedValue({
      items: [{ ...fassung, deletionPending: true }],
      coreContextPrefix: { title: true, documentType: false, documentDate: false },
      documentsAwaitingContextPrefixRerun: 0,
      pendingSchemaChanges: [
        {
          kind: 'FIELD_DELETION' as const,
          fieldKey: 'fassung',
          valueCode: null,
          targetCode: null,
          processedDocuments: 0,
          remainingDocuments: 4,
          correlationRef: 'metadata-field-delete-1',
        },
      ],
    })
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />)

    expect(
      await screen.findByText(/Feld „Fassung“ wird gelöscht: 4 Dokumente offen/),
    ).toBeInTheDocument()
    expect(screen.getByText('Fassung').closest('li')).toHaveTextContent(/· wird gelöscht$/)
    expect(screen.queryByRole('button', EDIT_FIELD)).not.toBeInTheDocument()
    await openValues(userEvent.setup())
    expect(screen.queryByRole('button', { name: 'Wert ergänzen' })).not.toBeInTheDocument()
  })
})
