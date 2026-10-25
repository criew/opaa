import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { answerConfirm, renderWithProviders } from '../../../test/test-utils'
import AssetOwnerSection from './AssetOwnerSection'
import type { SelectableGroupResponse, UserSummary } from '@/shared/api/types'

const { mockTransferAssetOwnership, mockGetUserSummaries, mockSearchSelectableGroups } = vi.hoisted(
  () => ({
    mockTransferAssetOwnership: vi.fn(async () => undefined),
    mockGetUserSummaries: vi.fn(async () => [] as UserSummary[]),
    mockSearchSelectableGroups: vi.fn(async () => [] as SelectableGroupResponse[]),
  }),
)

vi.mock('../api/assetApi', async () => {
  const actual = await vi.importActual<typeof import('../api/assetApi')>('../api/assetApi')
  return {
    ...actual,
    transferAssetOwnership: mockTransferAssetOwnership,
  }
})

vi.mock('@/features/principal/api/userApi', async () => {
  const actual = await vi.importActual<typeof import('@/features/principal/api/userApi')>(
    '@/features/principal/api/userApi',
  )
  return {
    ...actual,
    getUserSummaries: mockGetUserSummaries,
  }
})

vi.mock('@/features/principal/api/groupLookupApi', async () => {
  const actual = await vi.importActual<typeof import('@/features/principal/api/groupLookupApi')>(
    '@/features/principal/api/groupLookupApi',
  )
  return {
    ...actual,
    searchSelectableGroups: mockSearchSelectableGroups,
  }
})

const successor: UserSummary = {
  id: '11111111-2222-3333-4444-555555555555',
  email: 'nina@opaa.local',
  displayName: 'Nina Klein',
}

function renderSection(
  props: Partial<React.ComponentProps<typeof AssetOwnerSection>> = {},
  onTransferred = vi.fn(),
) {
  return renderWithProviders(
    <AssetOwnerSection
      assetType="KNOWLEDGE_LIBRARY"
      assetId="library-team"
      ownerType="USER"
      ownerName="Erika Mustermann"
      canTransfer
      onTransferred={onTransferred}
      {...props}
    />,
  )
}

async function chooseSuccessor(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: /^Übergeben\s?Eigentum$/ }))
  await user.type(screen.getByLabelText('Person oder Gruppe suchen'), 'Nina')
  await user.click(await screen.findByRole('option', { name: /Nina Klein/ }))
  await user.click(screen.getByRole('button', { name: 'Übergeben' }))
}

describe('AssetOwnerSection (#1941)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockTransferAssetOwnership.mockResolvedValue(undefined)
    mockGetUserSummaries.mockResolvedValue([successor])
    mockSearchSelectableGroups.mockResolvedValue([])
  })

  it('names the owning group and the kind of party in the row of the owner', () => {
    renderSection({ ownerType: 'GROUP', ownerName: 'Referat 50', canTransfer: false })

    const row = screen.getByRole('region', { name: 'Eigentümer' })
    expect(within(row).getByText('Referat 50')).toBeInTheDocument()
    expect(within(row).getByText('· Gruppe')).toBeInTheDocument()
  })

  it('names an owner that may not be named without inventing one', () => {
    renderSection({ ownerName: null, canTransfer: false })

    expect(screen.getByText('Nicht benannte Person')).toBeInTheDocument()
  })

  // Only the owner may hand over; for everyone else the section is information.
  it('offers the handover only where the caller holds the ownership', () => {
    const { unmount } = renderSection({ canTransfer: false })
    expect(screen.queryByRole('button', { name: /^Übergeben\s?Eigentum$/ })).not.toBeInTheDocument()
    unmount()

    renderSection()
    expect(screen.getByRole('button', { name: /^Übergeben\s?Eigentum$/ })).toBeInTheDocument()
  })

  it('asks back before handing the asset on and reloads afterwards', async () => {
    const onTransferred = vi.fn()
    renderSection({}, onTransferred)
    const user = userEvent.setup()

    await chooseSuccessor(user)
    await answerConfirm(user, /Eigentum an/, 'Übergeben')

    await waitFor(() => {
      expect(mockTransferAssetOwnership).toHaveBeenCalledWith('KNOWLEDGE_LIBRARY', 'library-team', {
        ownerType: 'USER',
        ownerId: successor.id,
      })
    })
    expect(onTransferred).toHaveBeenCalled()
  })

  it('does not hand the asset on when the question is answered with no', async () => {
    renderSection()
    const user = userEvent.setup()

    await chooseSuccessor(user)
    await answerConfirm(user, /Eigentum an/, 'Abbrechen')

    await waitFor(() => {
      expect(mockTransferAssetOwnership).not.toHaveBeenCalled()
    })
  })

  it('keeps a refusal in the section instead of losing it', async () => {
    mockTransferAssetOwnership.mockRejectedValueOnce(new Error('Kein Zugriff auf diese Bibliothek'))
    renderSection()
    const user = userEvent.setup()

    await chooseSuccessor(user)
    await answerConfirm(user, /Eigentum an/, 'Übergeben')

    expect(await screen.findByText('Kein Zugriff auf diese Bibliothek')).toBeInTheDocument()
  })
})
