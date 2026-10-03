import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { answerConfirm, renderWithProviders } from '../../test/test-utils'
import AssetSpacesList from './AssetSpacesList'
import type { AssetSpaceAssociationListResponse, SpaceListResponse } from '../../types/api'

const { mockGetAssetSpaceAssociations, mockDetachSpaceAsset, mockGetSpaces } = vi.hoisted(() => ({
  mockGetAssetSpaceAssociations: vi.fn<() => Promise<AssetSpaceAssociationListResponse>>(),
  mockDetachSpaceAsset: vi.fn(async () => {}),
  mockGetSpaces: vi.fn<() => Promise<SpaceListResponse[]>>(async () => []),
}))

vi.mock('../../services/assetApi', async () => {
  const actual =
    await vi.importActual<typeof import('../../services/assetApi')>('../../services/assetApi')
  return {
    ...actual,
    getAssetSpaceAssociations: mockGetAssetSpaceAssociations,
    detachSpaceAsset: mockDetachSpaceAsset,
  }
})

vi.mock('../../services/spaceApi', async () => {
  const actual =
    await vi.importActual<typeof import('../../services/spaceApi')>('../../services/spaceApi')
  return { ...actual, getSpaces: mockGetSpaces }
})

function spaceWithRole(id: string, userRole: SpaceListResponse['userRole']): SpaceListResponse {
  return {
    id,
    name: id,
    isDefault: false,
    archived: false,
    memberCount: 1,
    memberships: { groupCount: 0, userCount: 1 },
    userRole,
    createdAt: '2026-09-01T10:00:00Z',
    updatedAt: '2026-09-01T10:00:00Z',
  }
}

describe('AssetSpacesList (#1939, #2208)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetSpaces.mockResolvedValue([])
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
    expect(screen.getByText('+ 2 weitere Spaces, die Sie nicht sehen dürfen')).toBeInTheDocument()
    await waitFor(() => expect(mockGetSpaces).toHaveBeenCalled())
    expect(screen.queryByRole('button', { name: /aktionen für space/i })).not.toBeInTheDocument()
    expect(screen.queryByText('nicht alle Mitglieder lesen')).not.toBeInTheDocument()
  })

  it('names the single hidden space in the singular', async () => {
    mockGetAssetSpaceAssociations.mockResolvedValue({ items: [], hiddenCount: 1 })

    renderWithProviders(
      <AssetSpacesList assetType="KNOWLEDGE_LIBRARY" assetId="lib-1" canManage={false} />,
    )

    expect(
      await screen.findByText('+ 1 weiterer Space, den Sie nicht sehen dürfen'),
    ).toBeInTheDocument()
    // Eine gekürzte Liste ist nicht dasselbe wie „keiner Zuordnung" - der Leerzustand darf hier
    // nicht erscheinen.
    expect(screen.queryByText(/keinem Space zugeordnet/i)).not.toBeInTheDocument()
  })

  it('gives a manager who associated when, the reader-circle hint and the detach entry', async () => {
    mockGetAssetSpaceAssociations.mockResolvedValue({
      items: [
        {
          spaceId: 'space-1',
          spaceName: 'Disziplinarverfahren 2026',
          narrowerReaderCircle: true,
          createdByUserId: 'user-1',
          createdByDisplayName: 'Thomas Klein',
          createdAt: '2026-10-02T10:00:00Z',
        },
      ],
      hiddenCount: 0,
    })

    renderWithProviders(<AssetSpacesList assetType="KNOWLEDGE_LIBRARY" assetId="lib-1" canManage />)

    expect(await screen.findByText('Disziplinarverfahren 2026')).toBeInTheDocument()
    expect(screen.getByText(/zugeordnet von Thomas Klein · 02\.10\.2026/)).toBeInTheDocument()
    expect(screen.getByText('nicht alle Mitglieder lesen')).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Aktionen für Space „Disziplinarverfahren 2026“' }),
    ).toBeInTheDocument()
  })

  // Lösen darf, wer den Space kuratiert - auch ohne die Bibliothek zu verwalten (#2208).
  it('lets a curator of the space detach it through the row menu, and only that row', async () => {
    mockGetSpaces.mockResolvedValue([
      spaceWithRole('space-curated', 'CURATOR'),
      spaceWithRole('space-member', 'MEMBER'),
    ])
    mockGetAssetSpaceAssociations.mockResolvedValue({
      items: [
        { spaceId: 'space-curated', spaceName: 'Infotheke Bürgerbüro' },
        { spaceId: 'space-member', spaceName: 'Dienstbesprechung Bürgerbüro' },
      ],
      hiddenCount: 0,
    })
    const onChanged = vi.fn()
    renderWithProviders(
      <AssetSpacesList
        assetType="PROMPT_LIBRARY"
        assetId="prompt-1"
        canManage={false}
        onChanged={onChanged}
      />,
    )
    const user = userEvent.setup()

    const rowMenu = await screen.findByRole('button', {
      name: 'Aktionen für Space „Infotheke Bürgerbüro“',
    })
    expect(
      screen.queryByRole('button', { name: 'Aktionen für Space „Dienstbesprechung Bürgerbüro“' }),
    ).not.toBeInTheDocument()

    await user.click(rowMenu)
    const menu = await screen.findByRole('menu')
    await user.click(within(menu).getByRole('menuitem', { name: 'Aus Space lösen' }))
    await answerConfirm(user, 'Zuordnung zum Space „Infotheke Bürgerbüro“ lösen?', 'Lösen')

    await waitFor(() =>
      expect(mockDetachSpaceAsset).toHaveBeenCalledWith('space-curated', 'prompt-1'),
    )
    await waitFor(() => expect(screen.queryByText('Infotheke Bürgerbüro')).not.toBeInTheDocument())
    expect(onChanged).toHaveBeenCalled()
  })

  it('shows the empty state only when nothing is associated at all', async () => {
    mockGetAssetSpaceAssociations.mockResolvedValue({ items: [], hiddenCount: 0 })

    renderWithProviders(
      <AssetSpacesList assetType="KNOWLEDGE_LIBRARY" assetId="lib-1" canManage={false} />,
    )

    expect(await screen.findByText(/keinem Space zugeordnet/i)).toBeInTheDocument()
  })
})
