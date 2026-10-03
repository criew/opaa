import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import { useAuthStore } from '../../stores/authStore'
import AssetAccessDerivationSection from './AssetAccessDerivationSection'

const { mockGetAssetAccessDerivation } = vi.hoisted(() => ({
  mockGetAssetAccessDerivation: vi.fn(async () => ({
    assetType: 'PROMPT_LIBRARY' as const,
    assetId: 'prompts-1',
    effectiveRole: 'VIEWER' as const,
    pathsWithheld: false,
    paths: [
      {
        basis: 'ORGANIZATION_WIDE' as const,
        assetRole: 'VIEWER' as const,
        spaceRole: null,
        since: '2026-03-01T10:00:00Z',
        group: null,
      },
    ],
  })),
}))

vi.mock('../../services/assetApi', async () => {
  const actual =
    await vi.importActual<typeof import('../../services/assetApi')>('../../services/assetApi')
  return { ...actual, getAssetAccessDerivation: mockGetAssetAccessDerivation }
})

/** #2205: dieselbe Herleitung wie am Space, mit dem eigenen Namen und „Schließen“. */
describe('AssetAccessDerivationSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAuthStore.setState({
      user: { id: 'u1', email: 'maria@opaa.local', displayName: 'Maria Weber', systemRole: 'USER' },
    })
  })

  it('names the reader and closes back to the button that opened it', async () => {
    renderWithProviders(
      <AssetAccessDerivationSection assetType="PROMPT_LIBRARY" assetId="prompts-1" />,
    )
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Herleitung anzeigen' }))
    expect(
      await screen.findByText(
        'Maria Weber darf diese Prompt-Bibliothek lesen – für alle Konten freigegeben.',
      ),
    ).toBeInTheDocument()
    expect(mockGetAssetAccessDerivation).toHaveBeenCalledWith('PROMPT_LIBRARY', 'prompts-1')

    await user.click(screen.getByRole('button', { name: 'Schließen' }))

    expect(screen.queryByText(/Maria Weber darf/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Herleitung anzeigen' })).toHaveFocus()
  })
})
