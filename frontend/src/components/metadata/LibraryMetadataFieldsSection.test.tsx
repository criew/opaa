import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import LibraryMetadataFieldsSection from './LibraryMetadataFieldsSection'

const { mockList, mockCreate, mockDelete, mockFieldUsage, mockValueUsage, mockRemap } = vi.hoisted(
  () => ({
    mockList: vi.fn(),
    mockCreate: vi.fn(),
    mockDelete: vi.fn(),
    mockFieldUsage: vi.fn(),
    mockValueUsage: vi.fn(),
    mockRemap: vi.fn(),
  }),
)

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
    mockList.mockResolvedValue({ items: [fassung] })
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
    await user.click(screen.getByRole('button', { name: 'Wert Fassung 2026 entfernen' }))

    expect(await screen.findByText(/3 Dokument\(e\) tragen „F2026“/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Abbildung bestätigen' }))
    expect(mockRemap).toHaveBeenCalledWith('library-team', 'fassung', 'F2026', null)
  })

  it('shows the Folgekosten of a field deletion before it is confirmed', async () => {
    const user = userEvent.setup()
    renderWithProviders(<LibraryMetadataFieldsSection libraryId="library-team" canManageSchema />)

    await user.click(await screen.findByRole('button', { name: 'Löschen' }))

    expect(
      await screen.findByText(/4 Dokument\(e\) tragen einen Wert für „Fassung“/),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Endgültig löschen' }))
    expect(mockDelete).toHaveBeenCalledWith('library-team', 'fassung')
  })

  it('offers no schema change without the management right', async () => {
    renderWithProviders(
      <LibraryMetadataFieldsSection libraryId="library-team" canManageSchema={false} />,
    )

    await screen.findByText('Fassung')
    expect(screen.queryByRole('button', { name: 'Feld anlegen' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Löschen' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Wert ergänzen' })).not.toBeInTheDocument()
  })
})
