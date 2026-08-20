import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import EditLibrarySourceDialog from './EditLibrarySourceDialog'
import { useLibraryStore } from '../stores/libraryStore'
import type {
  LibraryResponse,
  LibraryUpdateRequest,
  SourceConnectionTestResponse,
} from '../types/api'

const { mockUpdateLibrary, mockTestLibrarySource } = vi.hoisted(() => ({
  mockUpdateLibrary: vi.fn(async (_id: string, request: LibraryUpdateRequest) => {
    return { id: _id, ...request } as unknown as LibraryResponse
  }),
  mockTestLibrarySource: vi.fn(
    async () =>
      ({
        reachable: true,
        documentCount: 3,
        message: 'Webverzeichnis erreichbar, 3 unterstützte Dokumente auf oberster Ebene gefunden.',
      }) as SourceConnectionTestResponse,
  ),
}))

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api')
  return {
    ...actual,
    updateLibrary: mockUpdateLibrary,
    getLibraries: vi.fn(async () => []),
    getLibrary: vi.fn(async () => undefined),
    testLibrarySource: mockTestLibrarySource,
  }
})

const filesystemLibrary = {
  name: 'Serververzeichnis',
  description: 'Interne Dokumente',
  visibility: 'SHARED' as const,
  listed: true,
  sourceType: 'FILESYSTEM' as const,
  sourcePath: '/data/dokumente',
  sourceUrl: null,
  sourceProxy: null,
  sourceInsecureSsl: null,
}

const httpDirectoryLibrary = {
  name: 'Webverzeichnis',
  description: null,
  visibility: 'ORGANIZATION' as const,
  listed: false,
  sourceType: 'HTTP_DIRECTORY' as const,
  sourcePath: null,
  sourceUrl: 'https://old.example.com/documents/',
  sourceProxy: 'proxy.old.example.com:8080',
  sourceInsecureSsl: true,
  sourceCredentialsSet: true,
}

const httpDirectoryLibraryWithoutCredentials = {
  ...httpDirectoryLibrary,
  sourceCredentialsSet: false,
}

describe('EditLibrarySourceDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useLibraryStore.setState({ libraries: [], libraryDetails: {}, isLoading: false, error: null })
  })

  it('prefills the directory path for a FILESYSTEM library and never shows a credentials field', async () => {
    renderWithProviders(
      <EditLibrarySourceDialog
        open
        onClose={vi.fn()}
        libraryId="library-1"
        library={filesystemLibrary}
      />,
    )

    expect(await screen.findByLabelText(/verzeichnispfad/i)).toHaveValue('/data/dokumente')
    expect(screen.queryByLabelText(/zugangsdaten/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/adresse \(url\)/i)).not.toBeInTheDocument()
  })

  it('shows the hint that the change only takes effect on the next indexing run', async () => {
    renderWithProviders(
      <EditLibrarySourceDialog
        open
        onClose={vi.fn()}
        libraryId="library-1"
        library={filesystemLibrary}
      />,
    )

    expect(
      await screen.findByText(/wirkt erst mit dem nächsten indizierungslauf/i),
    ).toBeInTheDocument()
  })

  it('rejects a relative directory path for a FILESYSTEM library', async () => {
    renderWithProviders(
      <EditLibrarySourceDialog
        open
        onClose={vi.fn()}
        libraryId="library-1"
        library={filesystemLibrary}
      />,
    )
    const user = userEvent.setup()

    const pathField = await screen.findByLabelText(/verzeichnispfad/i)
    await user.clear(pathField)
    await user.type(pathField, 'relativ/pfad')
    await user.click(screen.getByRole('button', { name: /^speichern$/i }))

    expect(
      await screen.findByText(/absoluter pfad sein, z\. b\. \/data\/dokumente/i),
    ).toBeInTheDocument()
    expect(mockUpdateLibrary).not.toHaveBeenCalled()
  })

  it('saves a new directory path together with the unrelated name/description/visibility/listed fields', async () => {
    const onClose = vi.fn()
    renderWithProviders(
      <EditLibrarySourceDialog
        open
        onClose={onClose}
        libraryId="library-1"
        library={filesystemLibrary}
      />,
    )
    const user = userEvent.setup()

    const pathField = await screen.findByLabelText(/verzeichnispfad/i)
    await user.clear(pathField)
    await user.type(pathField, '/data/neu')
    await user.click(screen.getByRole('button', { name: /^speichern$/i }))

    await waitFor(() => {
      expect(mockUpdateLibrary).toHaveBeenCalledWith('library-1', {
        name: 'Serververzeichnis',
        description: 'Interne Dokumente',
        visibility: 'SHARED',
        listed: true,
        sourcePath: '/data/neu',
        sourceUrl: undefined,
        sourceProxy: undefined,
        sourceCredentials: undefined,
        sourceInsecureSsl: false,
      } satisfies LibraryUpdateRequest)
    })
    expect(onClose).toHaveBeenCalled()
  })

  it('prefills URL, proxy and SSL switch for an HTTP_DIRECTORY library, leaving credentials blank', async () => {
    renderWithProviders(
      <EditLibrarySourceDialog
        open
        onClose={vi.fn()}
        libraryId="library-2"
        library={httpDirectoryLibrary}
      />,
    )

    expect(await screen.findByLabelText(/adresse \(url\)/i)).toHaveValue(
      'https://old.example.com/documents/',
    )
    expect(screen.getByLabelText(/^proxy/i)).toHaveValue('proxy.old.example.com:8080')
    expect(screen.getByRole('switch', { name: /zertifikatsprüfung aussetzen/i })).toBeChecked()
    expect(screen.getByLabelText(/neue zugangsdaten/i)).toHaveValue('')
  })

  it('requires an http(s) URL for an HTTP_DIRECTORY library', async () => {
    renderWithProviders(
      <EditLibrarySourceDialog
        open
        onClose={vi.fn()}
        libraryId="library-2"
        library={httpDirectoryLibrary}
      />,
    )
    const user = userEvent.setup()

    const urlField = await screen.findByLabelText(/adresse \(url\)/i)
    await user.clear(urlField)
    await user.type(urlField, 'ftp://files.example.com')
    await user.click(screen.getByRole('button', { name: /^speichern$/i }))

    expect(
      await screen.findByText(/muss mit http:\/\/ oder https:\/\/ beginnen/i),
    ).toBeInTheDocument()
    expect(mockUpdateLibrary).not.toHaveBeenCalled()
  })

  it('leaves stored credentials untouched (omits the field) when only the path changes on the same host', async () => {
    renderWithProviders(
      <EditLibrarySourceDialog
        open
        onClose={vi.fn()}
        libraryId="library-2"
        library={httpDirectoryLibrary}
      />,
    )
    const user = userEvent.setup()

    const urlField = await screen.findByLabelText(/adresse \(url\)/i)
    await user.clear(urlField)
    await user.type(urlField, 'https://old.example.com/other-documents/')
    await user.click(screen.getByRole('button', { name: /^speichern$/i }))

    await waitFor(() => {
      expect(mockUpdateLibrary).toHaveBeenCalledWith(
        'library-2',
        expect.objectContaining({
          sourceUrl: 'https://old.example.com/other-documents/',
          sourceCredentials: undefined,
        }),
      )
    })
  })

  it('shows an accurate hint when no credentials are stored for the source, and never claims otherwise', async () => {
    renderWithProviders(
      <EditLibrarySourceDialog
        open
        onClose={vi.fn()}
        libraryId="library-2"
        library={httpDirectoryLibraryWithoutCredentials}
      />,
    )

    expect(await screen.findByText(/aktuell keine zugangsdaten hinterlegt/i)).toBeInTheDocument()
    expect(
      screen.queryByText(/leer lassen, um die bestehenden zugangsdaten/i),
    ).not.toBeInTheDocument()
  })

  it('warns that changing the address to another host discards the stored credentials', async () => {
    renderWithProviders(
      <EditLibrarySourceDialog
        open
        onClose={vi.fn()}
        libraryId="library-2"
        library={httpDirectoryLibrary}
      />,
    )
    const user = userEvent.setup()

    expect(screen.getByText(/leer lassen, um die bestehenden zugangsdaten/i)).toBeInTheDocument()

    const urlField = await screen.findByLabelText(/adresse \(url\)/i)
    await user.clear(urlField)
    await user.type(urlField, 'https://attacker.example.com/documents/')

    expect(
      await screen.findByText(
        /zeigt auf einen anderen server.*bestehenden zugangsdaten werden dabei verworfen/i,
      ),
    ).toBeInTheDocument()
  })

  it('sends newly entered credentials to replace the stored ones', async () => {
    renderWithProviders(
      <EditLibrarySourceDialog
        open
        onClose={vi.fn()}
        libraryId="library-2"
        library={httpDirectoryLibrary}
      />,
    )
    const user = userEvent.setup()

    await user.type(await screen.findByLabelText(/neue zugangsdaten/i), 'admin:new-secret')
    await user.click(screen.getByRole('button', { name: /^speichern$/i }))

    await waitFor(() => {
      expect(mockUpdateLibrary).toHaveBeenCalledWith(
        'library-2',
        expect.objectContaining({ sourceCredentials: 'admin:new-secret' }),
      )
    })
  })

  it('keeps the credentials field out of the browser password manager', async () => {
    renderWithProviders(
      <EditLibrarySourceDialog
        open
        onClose={vi.fn()}
        libraryId="library-2"
        library={httpDirectoryLibrary}
      />,
    )

    expect(await screen.findByLabelText(/neue zugangsdaten/i)).toHaveAttribute(
      'autocomplete',
      'new-password',
    )
  })

  it('shows a German backend validation error without closing the dialog', async () => {
    mockUpdateLibrary.mockRejectedValueOnce(
      new Error('sourceUrl muss mit http oder https beginnen'),
    )
    const onClose = vi.fn()
    renderWithProviders(
      <EditLibrarySourceDialog
        open
        onClose={onClose}
        libraryId="library-2"
        library={httpDirectoryLibrary}
      />,
    )
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: /^speichern$/i }))

    expect(
      await screen.findByText('sourceUrl muss mit http oder https beginnen'),
    ).toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
  })

  describe('Verbindung testen (#544)', () => {
    it('offers a test button for a connector library', async () => {
      renderWithProviders(
        <EditLibrarySourceDialog
          open
          onClose={vi.fn()}
          libraryId="library-2"
          library={httpDirectoryLibrary}
        />,
      )

      expect(await screen.findByRole('button', { name: /verbindung testen/i })).toBeInTheDocument()
    })

    it('sends the libraryId together with the entered configuration when credentials are left blank, reusing the stored ones', async () => {
      renderWithProviders(
        <EditLibrarySourceDialog
          open
          onClose={vi.fn()}
          libraryId="library-2"
          library={httpDirectoryLibrary}
        />,
      )
      const user = userEvent.setup()

      await user.click(await screen.findByRole('button', { name: /verbindung testen/i }))

      await waitFor(() => {
        expect(mockTestLibrarySource).toHaveBeenCalledWith(
          expect.objectContaining({
            sourceType: 'HTTP_DIRECTORY',
            sourceUrl: 'https://old.example.com/documents/',
            sourceCredentials: undefined,
            libraryId: 'library-2',
          }),
        )
      })
      expect(
        await screen.findByText(
          'Webverzeichnis erreichbar, 3 unterstützte Dokumente auf oberster Ebene gefunden.',
        ),
      ).toBeInTheDocument()
    })

    it('omits the libraryId once new credentials are entered, since they take precedence over the stored ones', async () => {
      renderWithProviders(
        <EditLibrarySourceDialog
          open
          onClose={vi.fn()}
          libraryId="library-2"
          library={httpDirectoryLibrary}
        />,
      )
      const user = userEvent.setup()

      await user.type(await screen.findByLabelText(/neue zugangsdaten/i), 'admin:new-secret')
      await user.click(screen.getByRole('button', { name: /verbindung testen/i }))

      await waitFor(() => {
        expect(mockTestLibrarySource).toHaveBeenCalledWith(
          expect.objectContaining({
            sourceCredentials: 'admin:new-secret',
            libraryId: undefined,
          }),
        )
      })
    })

    it('shows an unreachable result as a warning, not an error, since the test itself succeeded', async () => {
      mockTestLibrarySource.mockResolvedValueOnce({
        reachable: false,
        documentCount: null,
        message: 'Die Zugangsdaten wurden vom Server abgelehnt (HTTP 401 Unauthorized).',
      })
      renderWithProviders(
        <EditLibrarySourceDialog
          open
          onClose={vi.fn()}
          libraryId="library-2"
          library={httpDirectoryLibrary}
        />,
      )
      const user = userEvent.setup()

      await user.click(await screen.findByRole('button', { name: /verbindung testen/i }))

      const alert = await screen.findByText(
        'Die Zugangsdaten wurden vom Server abgelehnt (HTTP 401 Unauthorized).',
      )
      expect(alert.closest('[class*="colorWarning"]')).not.toBeNull()
    })

    it('shows a German backend error when the test call itself fails, e.g. missing MANAGER role', async () => {
      mockTestLibrarySource.mockRejectedValueOnce(new Error('Kein Zugriff auf diese Bibliothek'))
      renderWithProviders(
        <EditLibrarySourceDialog
          open
          onClose={vi.fn()}
          libraryId="library-2"
          library={httpDirectoryLibrary}
        />,
      )
      const user = userEvent.setup()

      await user.click(await screen.findByRole('button', { name: /verbindung testen/i }))

      expect(await screen.findByText('Kein Zugriff auf diese Bibliothek')).toBeInTheDocument()
    })

    it('invalidates a previous test result once a field the test depends on changes', async () => {
      renderWithProviders(
        <EditLibrarySourceDialog
          open
          onClose={vi.fn()}
          libraryId="library-2"
          library={httpDirectoryLibrary}
        />,
      )
      const user = userEvent.setup()

      await user.click(await screen.findByRole('button', { name: /verbindung testen/i }))
      expect(
        await screen.findByText(
          'Webverzeichnis erreichbar, 3 unterstützte Dokumente auf oberster Ebene gefunden.',
        ),
      ).toBeInTheDocument()

      await user.type(screen.getByLabelText(/^proxy/i), '1')

      expect(
        screen.queryByText(
          'Webverzeichnis erreichbar, 3 unterstützte Dokumente auf oberster Ebene gefunden.',
        ),
      ).not.toBeInTheDocument()
    })

    it('does not test the library before saving, keeping the two actions separate', async () => {
      renderWithProviders(
        <EditLibrarySourceDialog
          open
          onClose={vi.fn()}
          libraryId="library-2"
          library={httpDirectoryLibrary}
        />,
      )
      const user = userEvent.setup()

      await user.click(await screen.findByRole('button', { name: /verbindung testen/i }))
      await waitFor(() => expect(mockTestLibrarySource).toHaveBeenCalledTimes(1))

      expect(mockUpdateLibrary).not.toHaveBeenCalled()
    })
  })
})
