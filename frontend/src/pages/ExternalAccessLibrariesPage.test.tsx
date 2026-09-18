import { screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import ExternalAccessLibrariesPage from './ExternalAccessLibrariesPage'
import type { ExternalAccessLibraryResponse } from '../types/api'

const { mockGetExternalAccessLibraries } = vi.hoisted(() => ({
  mockGetExternalAccessLibraries: vi.fn(),
}))

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api')
  return { ...actual, getExternalAccessLibraries: mockGetExternalAccessLibraries }
})

const released: ExternalAccessLibraryResponse = {
  libraryId: 'library-1',
  libraryName: 'Baugenehmigungen 2024',
  externalAccess: {
    libraryId: 'library-1',
    state: 'ACTIVE',
    expiresAt: '2027-03-01T22:59:59Z',
    setAt: '2026-03-01T10:00:00Z',
    setByDisplayName: 'Erika Mustermann',
    tokenCount: 4,
    maxReleaseDays: 365,
  },
}

describe('ExternalAccessLibrariesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('nennt jede freigegebene Bibliothek mit Setzendem, Datum und Ablauf', async () => {
    mockGetExternalAccessLibraries.mockResolvedValue([released])

    renderWithProviders(<ExternalAccessLibrariesPage />)

    expect(await screen.findByText('Baugenehmigungen 2024')).toBeInTheDocument()
    expect(screen.getByText(/Erika Mustermann/)).toBeInTheDocument()
    expect(screen.getByText(/4 Zugangstokens/)).toBeInTheDocument()
  })

  it('sagt es, wenn derzeit keine Bibliothek freigegeben ist', async () => {
    mockGetExternalAccessLibraries.mockResolvedValue([])

    renderWithProviders(<ExternalAccessLibrariesPage />)

    expect(
      await screen.findByText('Derzeit ist keine Bibliothek für Fremdzugänge freigegeben.'),
    ).toBeInTheDocument()
  })

  it('zeigt einen Fehler statt einer leeren Liste, wenn der Abruf scheitert', async () => {
    mockGetExternalAccessLibraries.mockRejectedValue(new Error('Keine Berechtigung'))

    renderWithProviders(<ExternalAccessLibrariesPage />)

    expect(await screen.findByText('Keine Berechtigung')).toBeInTheDocument()
  })
})
