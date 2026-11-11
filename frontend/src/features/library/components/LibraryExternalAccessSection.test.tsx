import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders, answerConfirm } from '../../../test/test-utils'
import LibraryExternalAccessSection from './LibraryExternalAccessSection'
import type { LibraryExternalAccessResponse, LibraryResponse } from '@/shared/api/types'

const { mockGetLibrary, mockUpdateLibraryExternalAccess } = vi.hoisted(() => ({
  mockGetLibrary: vi.fn(),
  mockUpdateLibraryExternalAccess: vi.fn(),
}))

vi.mock('../api/libraryApi', async () => {
  const actual = await vi.importActual<typeof import('../api/libraryApi')>('../api/libraryApi')
  return {
    ...actual,
    getLibrary: mockGetLibrary,
  }
})

vi.mock('../../external-access/api/externalAccessApi', async () => {
  const actual = await vi.importActual<
    typeof import('../../external-access/api/externalAccessApi')
  >('../../external-access/api/externalAccessApi')
  return {
    ...actual,
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

const active: LibraryExternalAccessResponse = {
  ...neverSet,
  state: 'ACTIVE',
  expiresAt: '2027-03-01T22:59:59Z',
  setAt: '2026-03-01T10:00:00Z',
  setByDisplayName: 'Erika Mustermann',
  tokenCount: 4,
}

describe('LibraryExternalAccessSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('zeigt eine nie freigegebene Bibliothek als Zeile mit „Freigeben"', async () => {
    mockGetLibrary.mockResolvedValue(library(neverSet))

    renderWithProviders(<LibraryExternalAccessSection libraryId="library-1" />)

    expect(await screen.findByText('Nicht freigegeben')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Freigeben/ })).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  /** The number is the only clue for the yearly renewal - names must not appear in the row. */
  it('nennt in der Zeile Ablauf und Anzahl der Zugangstokens, keine Person', async () => {
    mockGetLibrary.mockResolvedValue(library(active))

    renderWithProviders(<LibraryExternalAccessSection libraryId="library-1" />)

    expect(await screen.findByText(/Freigegeben bis .+ · in 4 Zugangstokens/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^Ändern/ })).toBeInTheDocument()
    expect(screen.queryByText(/Erika Mustermann/)).not.toBeInTheDocument()
  })

  it('setzt die Freigabe mit dem im Dialog eingegebenen Ablaufdatum', async () => {
    const user = userEvent.setup()
    mockGetLibrary.mockResolvedValue(library(neverSet))
    mockUpdateLibraryExternalAccess.mockResolvedValue({
      ...active,
      tokenCount: 0,
    })

    renderWithProviders(<LibraryExternalAccessSection libraryId="library-1" />)

    await user.click(await screen.findByRole('button', { name: /^Freigeben/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Externer Zugang' })
    const dateField = within(dialog).getByLabelText('Freigabe bis')
    await user.type(dateField, '2027-03-01')
    await user.click(within(dialog).getByRole('button', { name: 'Freigeben' }))

    await waitFor(() => expect(mockUpdateLibraryExternalAccess).toHaveBeenCalledTimes(1))
    const [libraryId, enabled, expiresAt] = mockUpdateLibraryExternalAccess.mock.calls[0]
    expect(libraryId).toBe('library-1')
    expect(enabled).toBe(true)
    expect(expiresAt).not.toBeNull()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  /** A release without an end is a ratchet - the client does not even ask for it. */
  it('verweigert das Setzen ohne Ablaufdatum, ohne das Backend zu fragen', async () => {
    const user = userEvent.setup()
    mockGetLibrary.mockResolvedValue(library(neverSet))

    renderWithProviders(<LibraryExternalAccessSection libraryId="library-1" />)

    await user.click(await screen.findByRole('button', { name: /^Freigeben/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Externer Zugang' })
    await user.click(within(dialog).getByRole('button', { name: 'Freigeben' }))

    expect(await within(dialog).findByText(/Bitte ein Ablaufdatum angeben/)).toBeInTheDocument()
    expect(mockUpdateLibraryExternalAccess).not.toHaveBeenCalled()
  })

  it('beendet eine Freigabe erst nach Bestätigung', async () => {
    const user = userEvent.setup()
    mockGetLibrary.mockResolvedValue(library(active))
    mockUpdateLibraryExternalAccess.mockResolvedValue({
      ...active,
      state: 'WITHDRAWN',
      setAt: '2026-09-18T10:00:00Z',
      tokenCount: 0,
    })

    renderWithProviders(<LibraryExternalAccessSection libraryId="library-1" />)

    await user.click(await screen.findByRole('button', { name: /^Ändern/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Externer Zugang' })
    await user.click(within(dialog).getByRole('button', { name: 'Freigabe widerrufen' }))
    await answerConfirm(user, 'Freigabe für externe KI-Werkzeuge widerrufen?', 'Widerrufen')

    await waitFor(() =>
      expect(mockUpdateLibraryExternalAccess).toHaveBeenCalledWith('library-1', false, null),
    )
    expect(await screen.findByText(/zurückgenommen/, { exact: false })).toBeInTheDocument()
  })

  /** Below the manager role the backend omits the field - then there is nothing to show. */
  it('zeigt nichts, wenn die Antwort die Freigabe gar nicht enthält', async () => {
    mockGetLibrary.mockResolvedValue(library(undefined))

    renderWithProviders(<LibraryExternalAccessSection libraryId="library-1" />)

    await waitFor(() => expect(mockGetLibrary).toHaveBeenCalled())
    expect(screen.queryByText('Externer Zugang')).not.toBeInTheDocument()
  })
})
