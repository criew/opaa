import { useState } from 'react'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import type {
  SourceBrowseRequest,
  SourceBrowseResponse,
  SourceConnectionTestRequest,
  SourceConnectionTestResponse,
  SourceTypeKey,
} from '../../types/api'
import { EMPTY_DROPBOX_VALUES, type DropboxSourceValues } from '../../utils/dropboxSource'
import DropboxSourceForm from './DropboxSourceForm'
import { connectionFields } from './sources/sourceConnection'

const { mockTestLibrarySource, mockBrowseSource } = vi.hoisted(() => ({
  mockTestLibrarySource:
    vi.fn<(request: SourceConnectionTestRequest) => Promise<SourceConnectionTestResponse>>(),
  mockBrowseSource:
    vi.fn<
      (sourceType: SourceTypeKey, request: SourceBrowseRequest) => Promise<SourceBrowseResponse>
    >(),
}))

vi.mock('../../services/libraryApi', async () => {
  const actual = await vi.importActual<typeof import('../../services/libraryApi')>(
    '../../services/libraryApi',
  )
  return { ...actual, testLibrarySource: mockTestLibrarySource, browseSource: mockBrowseSource }
})

const CONNECTION = {
  profileId: 'p-1',
  name: 'Dropbox der Verwaltung',
  serverUrl: 'https://api.dropboxapi.com',
  authMethod: 'OAUTH' as const,
  defaults: {},
}

function Harness({ onValues }: { onValues?: (values: DropboxSourceValues) => void }) {
  const [values, setValues] = useState<DropboxSourceValues>(EMPTY_DROPBOX_VALUES)
  return (
    <DropboxSourceForm
      mode="create"
      idPrefix="test"
      values={values}
      onChange={(patch) => {
        const next = { ...values, ...patch }
        setValues(next)
        onValues?.(next)
      }}
      connection={connectionFields({
        mode: 'create',
        sourceType: 'DROPBOX',
        idPrefix: 'test',
        credentialsStored: false,
        connection: CONNECTION,
        pendingConnectionId: 'pending-1',
      })}
    />
  )
}

describe('DropboxSourceForm', () => {
  beforeEach(() => {
    mockTestLibrarySource.mockReset()
    mockBrowseSource.mockReset()
  })

  it('asks for no secret and lists the folders below a path through the consent', async () => {
    const user = userEvent.setup()
    const seen: DropboxSourceValues[] = []
    mockBrowseSource.mockResolvedValue({
      complete: true,
      entries: [{ key: '/Akten', name: 'Akten' }],
    })
    renderWithProviders(<Harness onValues={(values) => seen.push(values)} />)

    expect(screen.queryByLabelText(/Passwort|Token/i)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Ordner laden' }))
    await user.click(await screen.findByRole('button', { name: 'Ordner /Akten übernehmen' }))

    expect(mockBrowseSource).toHaveBeenCalledWith(
      'DROPBOX',
      expect.objectContaining({
        connectionProfileId: 'p-1',
        pendingConnectionId: 'pending-1',
        query: { path: '/', teamSpace: false },
      }),
    )
    expect(seen.at(-1)?.folders).toBe('/Akten')
  })

  it('tests every folder with the team space it is set to', async () => {
    const user = userEvent.setup()
    mockTestLibrarySource.mockResolvedValue({
      reachable: true,
      message: 'Verbindung erfolgreich; der Ordner ist erreichbar.',
    })
    renderWithProviders(<Harness />)

    expect(screen.getByLabelText('Team-Bereich lesen')).toHaveAccessibleDescription(
      /Ordner des Team-Bereichs/,
    )
    await user.click(screen.getByLabelText('Team-Bereich lesen'))
    await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))

    await waitFor(() =>
      expect(
        screen.getByText('Verbindung erfolgreich; der Ordner ist erreichbar.'),
      ).toBeInTheDocument(),
    )
    expect(mockTestLibrarySource).toHaveBeenCalledWith(
      expect.objectContaining({
        sourceType: 'DROPBOX',
        sourceSettings: { folders: ['/'], teamSpace: true },
        pendingConnectionId: 'pending-1',
      }),
    )
  })

  it('shows why the listing failed', async () => {
    const user = userEvent.setup()
    mockBrowseSource.mockResolvedValue({
      complete: false,
      entries: [],
      message: 'Das verbundene Dropbox-Konto gehört zu keinem Team mit Team-Bereich.',
    })
    renderWithProviders(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Ordner laden' }))

    expect(await screen.findByText(/keinem Team mit Team-Bereich/)).toBeInTheDocument()
  })
})
