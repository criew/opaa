import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import LibraryExternalAccessSection from './LibraryExternalAccessSection'
import type { LibraryExternalAccessResponse, LibraryResponse } from '../../types/api'

const { mockGetLibrary, mockUpdateLibraryExternalAccess } = vi.hoisted(() => ({
  mockGetLibrary: vi.fn(),
  mockUpdateLibraryExternalAccess: vi.fn(),
}))

vi.mock('../../services/api', async () => {
  const actual = await vi.importActual<typeof import('../../services/api')>('../../services/api')
  return {
    ...actual,
    getLibrary: mockGetLibrary,
    updateLibraryExternalAccess: mockUpdateLibraryExternalAccess,
  }
})

function library(externalAccess: LibraryExternalAccessResponse | undefined): LibraryResponse {
  return {
    id: 'library-1',
    name: 'Baugenehmigungen 2024',
    ownerType: 'USER',
    ownerId: 'user-1',
    reach: { allAccounts: false, groupCount: 0, userCount: 1 },
    listed: false,
    myRole: 'MANAGER',
    sourceType: 'UPLOAD',
    diagnosticsLocked: true,
    diagnosticsLockToggleable: false,
    externalAccess,
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  }
}

const neverSet: LibraryExternalAccessResponse = {
  libraryId: 'library-1',
  state: 'NEVER_SET',
  expiresAt: null,
  setAt: null,
  setByDisplayName: null,
  tokenCount: 0,
  maxReleaseDays: 365,
}

describe('LibraryExternalAccessSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('zeigt eine nie freigegebene Bibliothek als nicht freigegeben', async () => {
    mockGetLibrary.mockResolvedValue(library(neverSet))

    renderWithProviders(<LibraryExternalAccessSection libraryId="library-1" />)

    const toggle = await screen.findByRole('switch', { name: 'Über Fremdzugänge nutzbar' })
    expect(toggle).not.toBeChecked()
    expect(
      screen.getByText(/noch nie für Fremdzugänge freigegeben/, { exact: false }),
    ).toBeInTheDocument()
  })

  /** Die Zahl ist der einzige Anhalt der jährlichen Erneuerung - Namen dürfen dort nicht stehen. */
  it('nennt die Anzahl der Zugangstokens und keine Person, die eines hält', async () => {
    mockGetLibrary.mockResolvedValue(
      library({
        ...neverSet,
        state: 'ACTIVE',
        expiresAt: '2027-03-01T22:59:59Z',
        setAt: '2026-03-01T10:00:00Z',
        setByDisplayName: 'Erika Mustermann',
        tokenCount: 4,
      }),
    )

    renderWithProviders(<LibraryExternalAccessSection libraryId="library-1" />)

    expect(await screen.findByText('Derzeit in 4 Zugangstokens enthalten.')).toBeInTheDocument()
    expect(screen.queryByText(/Erika Mustermann/)).not.toBeInTheDocument()
  })

  it('setzt die Freigabe mit dem eingegebenen Ablaufdatum', async () => {
    const user = userEvent.setup()
    mockGetLibrary.mockResolvedValue(library(neverSet))
    mockUpdateLibraryExternalAccess.mockResolvedValue({
      ...neverSet,
      state: 'ACTIVE',
      expiresAt: '2027-03-01T22:59:59.999Z',
    })

    renderWithProviders(<LibraryExternalAccessSection libraryId="library-1" />)

    const dateField = await screen.findByLabelText('Freigabe bis')
    await user.clear(dateField)
    await user.type(dateField, '2027-03-01')
    await user.click(screen.getByRole('switch', { name: 'Über Fremdzugänge nutzbar' }))

    await waitFor(() => expect(mockUpdateLibraryExternalAccess).toHaveBeenCalledTimes(1))
    const [libraryId, enabled, expiresAt] = mockUpdateLibraryExternalAccess.mock.calls[0]
    expect(libraryId).toBe('library-1')
    expect(enabled).toBe(true)
    expect(expiresAt).not.toBeNull()
  })

  /** Eine Freigabe ohne Ende ist eine Ratsche - der Client fragt gar nicht erst danach. */
  it('verweigert das Setzen ohne Ablaufdatum, ohne das Backend zu fragen', async () => {
    const user = userEvent.setup()
    mockGetLibrary.mockResolvedValue(library(neverSet))

    renderWithProviders(<LibraryExternalAccessSection libraryId="library-1" />)

    await user.click(await screen.findByRole('switch', { name: 'Über Fremdzugänge nutzbar' }))

    expect(await screen.findByText(/Bitte ein Ablaufdatum angeben/)).toBeInTheDocument()
    expect(mockUpdateLibraryExternalAccess).not.toHaveBeenCalled()
  })

  it('nimmt eine gesetzte Freigabe ohne Ablaufdatum zurück', async () => {
    const user = userEvent.setup()
    mockGetLibrary.mockResolvedValue(
      library({
        ...neverSet,
        state: 'ACTIVE',
        expiresAt: '2027-03-01T22:59:59Z',
        setAt: '2026-03-01T10:00:00Z',
      }),
    )
    mockUpdateLibraryExternalAccess.mockResolvedValue({
      ...neverSet,
      state: 'WITHDRAWN',
      expiresAt: '2027-03-01T22:59:59Z',
      setAt: '2026-09-18T10:00:00Z',
    })

    renderWithProviders(<LibraryExternalAccessSection libraryId="library-1" />)

    await user.click(await screen.findByRole('switch', { name: 'Über Fremdzugänge nutzbar' }))

    await waitFor(() =>
      expect(mockUpdateLibraryExternalAccess).toHaveBeenCalledWith('library-1', false, null),
    )
    expect(await screen.findByText(/zurückgenommen/, { exact: false })).toBeInTheDocument()
  })

  /** Unterhalb der Verwalter-Rolle liefert das Backend das Feld nicht - dann gibt es nichts zu zeigen. */
  it('zeigt nichts, wenn die Antwort die Freigabe gar nicht enthält', async () => {
    mockGetLibrary.mockResolvedValue(library(undefined))

    renderWithProviders(<LibraryExternalAccessSection libraryId="library-1" />)

    await waitFor(() => expect(mockGetLibrary).toHaveBeenCalled())
    expect(
      screen.queryByRole('switch', { name: 'Über Fremdzugänge nutzbar' }),
    ).not.toBeInTheDocument()
  })
})
