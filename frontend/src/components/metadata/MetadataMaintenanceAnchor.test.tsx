import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import MetadataMaintenanceAnchor from './MetadataMaintenanceAnchor'

const { mockGetLibraryMetadataMaintenance } = vi.hoisted(() => ({
  mockGetLibraryMetadataMaintenance: vi.fn(),
}))

vi.mock('../../services/api', async () => {
  const actual = await vi.importActual<typeof import('../../services/api')>('../../services/api')
  return { ...actual, getLibraryMetadataMaintenance: mockGetLibraryMetadataMaintenance }
})

const maintenance = {
  libraryId: 'library-team',
  totalDocuments: 10,
  fields: [
    {
      fieldKey: 'title',
      label: 'Titel',
      totalDocuments: 10,
      documentsWithoutValue: 0,
      missingShare: 0,
      filledDocuments: 10,
      notDeterminableDocuments: 0,
    },
    {
      fieldKey: 'document_type',
      label: 'Dokumentart',
      totalDocuments: 10,
      documentsWithoutValue: 4,
      missingShare: 0.4,
      filledDocuments: 4,
      notDeterminableDocuments: 2,
    },
  ],
}

describe('MetadataMaintenanceAnchor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetLibraryMetadataMaintenance.mockResolvedValue(maintenance)
  })

  it('shows the absolute number and the share side by side and marks a maintained field', async () => {
    renderWithProviders(
      <MetadataMaintenanceAnchor
        libraryId="library-team"
        activeFieldKey={null}
        onShowMissing={vi.fn()}
        onClearFilter={vi.fn()}
      />,
    )

    expect(await screen.findByText('4 Dokumente ohne Wert (40 %)')).toBeInTheDocument()
    expect(screen.getByText('2 × kein Wert ermittelbar')).toBeInTheDocument()
    // A field nobody has to work on says so instead of showing a zero.
    expect(screen.getByText('vollständig gepflegt')).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Dokumente ohne Wert für Titel anzeigen' }),
    ).not.toBeInTheDocument()
  })

  it('opens the worklist of one field and offers to leave it again', async () => {
    const onShowMissing = vi.fn()
    const { rerender } = renderWithProviders(
      <MetadataMaintenanceAnchor
        libraryId="library-team"
        activeFieldKey={null}
        onShowMissing={onShowMissing}
        onClearFilter={vi.fn()}
      />,
    )
    const user = userEvent.setup()

    await user.click(
      await screen.findByRole('button', { name: 'Dokumente ohne Wert für Dokumentart anzeigen' }),
    )
    expect(onShowMissing).toHaveBeenCalledWith('document_type')

    const onClearFilter = vi.fn()
    rerender(
      <MetadataMaintenanceAnchor
        libraryId="library-team"
        activeFieldKey="document_type"
        onShowMissing={onShowMissing}
        onClearFilter={onClearFilter}
      />,
    )
    await user.click(await screen.findByRole('button', { name: 'Filter aufheben' }))
    expect(onClearFilter).toHaveBeenCalled()
  })
})
