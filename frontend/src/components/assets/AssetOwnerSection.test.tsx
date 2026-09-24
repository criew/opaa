import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { answerConfirm, renderWithProviders } from '../../test/test-utils'
import AssetOwnerSection from './AssetOwnerSection'
import type { SelectableGroupResponse, UserSummary } from '../../types/api'

const { mockTransferAssetOwnership, mockGetUserSummaries, mockSearchSelectableGroups } = vi.hoisted(
  () => ({
    mockTransferAssetOwnership: vi.fn(async () => undefined),
    mockGetUserSummaries: vi.fn(async () => [] as UserSummary[]),
    mockSearchSelectableGroups: vi.fn(async () => [] as SelectableGroupResponse[]),
  }),
)

vi.mock('../../services/api', async () => {
  const actual = await vi.importActual<typeof import('../../services/api')>('../../services/api')
  return {
    ...actual,
    transferAssetOwnership: mockTransferAssetOwnership,
    getUserSummaries: mockGetUserSummaries,
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
  await user.click(screen.getByRole('button', { name: 'Eigentum übergeben' }))
  await user.type(screen.getByLabelText('Person suchen'), 'Nina')
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

  it('names the owning group and says why a group survives a change of staff', () => {
    renderSection({ ownerType: 'GROUP', ownerName: 'Referat 50', canTransfer: false })

    expect(screen.getByText('Referat 50')).toBeInTheDocument()
    expect(screen.getByText(/Personalwechsel/)).toBeInTheDocument()
  })

  it('names an owner that may not be named without inventing one', () => {
    renderSection({ ownerName: null, canTransfer: false })

    expect(screen.getByText('Nicht benannte Person')).toBeInTheDocument()
  })

  // Übergeben darf nur der Eigentümer; für alle anderen ist der Abschnitt eine Auskunft.
  it('offers the handover only where the caller holds the ownership', () => {
    const { unmount } = renderSection({ canTransfer: false })
    expect(screen.queryByRole('button', { name: 'Eigentum übergeben' })).not.toBeInTheDocument()
    unmount()

    renderSection()
    expect(screen.getByRole('button', { name: 'Eigentum übergeben' })).toBeInTheDocument()
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
