import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import AssetSpacesList from './AssetSpacesList'
import type { AssetSpaceAssociationListResponse } from '../../types/api'

const { mockGetAssetSpaceAssociations } = vi.hoisted(() => ({
  mockGetAssetSpaceAssociations: vi.fn<() => Promise<AssetSpaceAssociationListResponse>>(),
}))

vi.mock('../../services/api', async () => {
  const actual = await vi.importActual<typeof import('../../services/api')>('../../services/api')
  return { ...actual, getAssetSpaceAssociations: mockGetAssetSpaceAssociations }
})

describe('AssetSpacesList (#1939)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('names what it may and counts the rest, without a detach affordance for a reader', async () => {
    mockGetAssetSpaceAssociations.mockResolvedValue({
      items: [{ spaceId: 'space-1', spaceName: 'Fachbereich Soziales' }],
      hiddenCount: 2,
    })

    renderWithProviders(
      <AssetSpacesList assetType="KNOWLEDGE_LIBRARY" assetId="lib-1" canManage={false} />,
    )

    expect(await screen.findByText('Fachbereich Soziales')).toBeInTheDocument()
    expect(screen.getByText('+ 2 weitere Spaces, die Sie nicht sehen können')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Lösen' })).not.toBeInTheDocument()
    expect(screen.queryByText('nicht alle Mitglieder lesen')).not.toBeInTheDocument()
  })

  it('names the single hidden space in the singular', async () => {
    mockGetAssetSpaceAssociations.mockResolvedValue({ items: [], hiddenCount: 1 })

    renderWithProviders(
      <AssetSpacesList assetType="KNOWLEDGE_LIBRARY" assetId="lib-1" canManage={false} />,
    )

    expect(
      await screen.findByText('+ 1 weiterer Space, den Sie nicht sehen können'),
    ).toBeInTheDocument()
    // Eine gekürzte Liste ist nicht dasselbe wie „keiner Zuordnung" - der Leerzustand darf hier
    // nicht erscheinen.
    expect(screen.queryByText(/keinem Space zugeordnet/i)).not.toBeInTheDocument()
  })

  it('gives a manager the reader-circle hint and the detach button', async () => {
    mockGetAssetSpaceAssociations.mockResolvedValue({
      items: [
        {
          spaceId: 'space-1',
          spaceName: 'Disziplinarverfahren 2026',
          narrowerReaderCircle: true,
          createdByUserId: 'user-1',
          createdAt: '2026-09-01T10:00:00Z',
        },
      ],
      hiddenCount: 0,
    })

    renderWithProviders(<AssetSpacesList assetType="KNOWLEDGE_LIBRARY" assetId="lib-1" canManage />)

    expect(await screen.findByText('Disziplinarverfahren 2026')).toBeInTheDocument()
    expect(screen.getByText('nicht alle Mitglieder lesen')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Lösen' })).toBeInTheDocument()
  })

  it('shows the empty state only when nothing is associated at all', async () => {
    mockGetAssetSpaceAssociations.mockResolvedValue({ items: [], hiddenCount: 0 })

    renderWithProviders(
      <AssetSpacesList assetType="KNOWLEDGE_LIBRARY" assetId="lib-1" canManage={false} />,
    )

    expect(await screen.findByText(/keinem Space zugeordnet/i)).toBeInTheDocument()
  })
})
