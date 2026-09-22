import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import AccessDerivation from './AccessDerivation'

const { mockGetLibraryAccessDerivation, mockGetSpaceAccessDerivation } = vi.hoisted(() => ({
  mockGetLibraryAccessDerivation: vi.fn(),
  mockGetSpaceAccessDerivation: vi.fn(),
}))

vi.mock('../../services/api', async () => {
  const actual = await vi.importActual<typeof import('../../services/api')>('../../services/api')
  return {
    ...actual,
    getLibraryAccessDerivation: mockGetLibraryAccessDerivation,
    getSpaceAccessDerivation: mockGetSpaceAccessDerivation,
  }
})

describe('AccessDerivation (#1822, ADR-0036 Entscheidung 9)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('names every own way, with the group, its origin and the mechanism behind it', async () => {
    mockGetLibraryAccessDerivation.mockResolvedValue({
      libraryId: 'library-1',
      effectiveRole: 'EDITOR',
      pathsWithheld: false,
      paths: [
        {
          basis: 'GROUP_GRANT',
          assetRole: 'VIEWER',
          spaceRole: null,
          since: '2026-03-01T10:00:00Z',
          group: {
            id: 'group-referat-50',
            name: 'Referat 50',
            origin: 'PROVIDER',
            mechanism: 'DIRECTORY',
            providerName: 'Verzeichnis Haus A',
          },
        },
        {
          basis: 'DIRECT_GRANT',
          assetRole: 'EDITOR',
          spaceRole: null,
          since: '2026-03-02T10:00:00Z',
          group: null,
        },
      ],
    })

    renderWithProviders(<AccessDerivation target={{ kind: 'library', libraryId: 'library-1' }} />)

    expect(await screen.findByText(/Wirksame Rolle: Bearbeiter/)).toBeInTheDocument()
    const groupPath = await screen.findByText(/Freigabe an eine Gruppe/)
    expect(groupPath).toHaveTextContent('Referat 50')
    expect(groupPath).toHaveTextContent('Verzeichnis Haus A')
    expect(groupPath).toHaveTextContent('gepflegt über Verzeichnisabgleich')
    expect(groupPath).toHaveTextContent('seit 1.3.2026')
    expect(screen.getByText(/Freigabe an Sie/)).toBeInTheDocument()
    // Die Mitglieder der Gruppe werden dabei nie offengelegt - das steht auch so da.
    expect(screen.getByText(/Mitglieder einer Gruppe werden hier nicht offengelegt/)).toBeVisible()
  })

  it('states the effective role without naming a way when a protected group is involved', async () => {
    mockGetSpaceAccessDerivation.mockResolvedValue({
      spaceId: 'space-1',
      userId: 'user-2',
      effectiveRole: 'MEMBER',
      pathsWithheld: true,
      paths: [],
    })

    renderWithProviders(
      <AccessDerivation target={{ kind: 'space', spaceId: 'space-1', userId: 'user-2' }} />,
    )

    expect(await screen.findByText(/Wirksame Rolle: Mitglied/)).toBeInTheDocument()
    expect(
      screen.getByText(/über eine geschützte Gruppe und wird hier nicht benannt/),
    ).toBeVisible()
    expect(mockGetSpaceAccessDerivation).toHaveBeenCalledWith('space-1', 'user-2')
  })

  it('names a failed request instead of showing an empty derivation', async () => {
    mockGetLibraryAccessDerivation.mockRejectedValue(new Error('Bibliothek nicht gefunden'))

    renderWithProviders(<AccessDerivation target={{ kind: 'library', libraryId: 'library-1' }} />)

    expect(await screen.findByText('Bibliothek nicht gefunden')).toBeInTheDocument()
  })
})
