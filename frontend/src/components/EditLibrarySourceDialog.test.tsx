import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import EditLibrarySourceDialog from './EditLibrarySourceDialog'
import { useLibraryStore } from '../stores/libraryStore'
import type {
  LibraryResponse,
  LibraryUpdateRequest,
  SourceConnectionTestRequest,
  SourceConnectionTestResponse,
} from '../types/api'

const { mockUpdateLibrary, mockTestLibrarySource } = vi.hoisted(() => ({
  mockUpdateLibrary: vi.fn(async (_id: string, request: LibraryUpdateRequest) => {
    return { id: _id, ...request } as unknown as LibraryResponse
  }),
  // Typed via the explicit generic (rather than LibraryCreatePage.test.tsx's parameterless
  // implementation) so mockTestLibrarySource.mock.calls[0] below is typed as a tuple with an
  // element at index 0, not `[]`.
  mockTestLibrarySource: vi.fn<
    (request: SourceConnectionTestRequest) => Promise<SourceConnectionTestResponse>
  >(async () => ({
    reachable: true,
    documentCount: 3,
    message: 'Webverzeichnis erreichbar, 3 unterstützte Dokumente auf oberster Ebene gefunden.',
  })),
}))

const { mockListConfluenceSpaces } = vi.hoisted(() => ({
  mockListConfluenceSpaces: vi.fn(async () => ({
    spaces: [
      { key: 'BAU', name: 'Bauamt' },
      { key: 'HR', name: 'Personal' },
      { key: 'IT', name: 'IT-Betrieb' },
    ],
  })),
}))

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api')
  return {
    ...actual,
    updateLibrary: mockUpdateLibrary,
    getLibraries: vi.fn(async () => []),
    getLibrary: vi.fn(async () => undefined),
    testLibrarySource: mockTestLibrarySource,
    listConfluenceSpaces: mockListConfluenceSpaces,
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
          expect.objectContaining({ sourceCredentials: 'admin:new-secret' }),
        )
      })
      // #615 review, nit c: objectContaining({ libraryId: undefined }) alone does not prove the
      // key is actually absent from the network payload - an explicitly assigned `libraryId:
      // undefined` property still exists on the JS object itself (toHaveProperty would find it
      // too), it is only JSON.stringify (the real serialization the HTTP client performs) that
      // drops an undefined-valued key. Asserting on the serialized form is what actually proves
      // the backend never sees the key.
      const [requestBody] = mockTestLibrarySource.mock.calls[0]
      expect(JSON.stringify(requestBody)).not.toContain('libraryId')
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

  describe('Confluence library (#1135, ADR-0023)', () => {
    const confluenceLibrary = {
      name: 'Wiki Bauamt',
      description: null,
      visibility: 'SHARED' as const,
      listed: false,
      sourceType: 'CONFLUENCE' as const,
      sourcePath: null,
      sourceUrl: 'https://wiki.behoerde.example/confluence',
      sourceProxy: null,
      sourceInsecureSsl: false,
      sourceCredentialsSet: true,
      confluenceEdition: 'DATA_CENTER' as const,
      confluenceSpaces: [{ key: 'BAU', name: 'Bauamt' }],
    }

    it('shows the fixed edition, loads the spaces with the stored token and saves the new selection without touching credentials', async () => {
      const user = userEvent.setup()
      renderWithProviders(
        <EditLibrarySourceDialog
          open
          onClose={() => {}}
          libraryId="lib-wiki"
          library={confluenceLibrary}
        />,
      )

      expect(screen.getByTestId('edit-source-confluence-edition')).toHaveTextContent(
        'Confluence Data Center',
      )
      expect(screen.queryByRole('button', { name: 'Edition erkennen' })).not.toBeInTheDocument()
      // stored credentials stand: the listing loads right away through the library
      await waitFor(() =>
        expect(mockListConfluenceSpaces).toHaveBeenCalledWith(
          expect.objectContaining({ libraryId: 'lib-wiki', sourceCredentials: undefined }),
        ),
      )
      const picker = await screen.findByLabelText(/Spaces suchen und auswählen/)
      await user.click(picker)
      await user.type(picker, 'Personal')
      await user.click(await screen.findByRole('option', { name: /Personal \(HR\)/ }))
      await user.click(screen.getByRole('button', { name: 'Speichern' }))

      await waitFor(() => expect(mockUpdateLibrary).toHaveBeenCalledTimes(1))
      const [, request] = mockUpdateLibrary.mock.calls[0]
      expect(request.confluenceSpaces).toEqual([
        { key: 'BAU', name: 'Bauamt' },
        { key: 'HR', name: 'Personal' },
      ])
      expect(request.confluenceEdition).toBe('DATA_CENTER')
      expect(request.sourceUrl).toBe('https://wiki.behoerde.example/confluence')
      expect(request.sourceCredentials).toBeUndefined()
    }, 15000)

    it('requires a fresh connection test once a new token is typed', async () => {
      const user = userEvent.setup()
      renderWithProviders(
        <EditLibrarySourceDialog
          open
          onClose={() => {}}
          libraryId="lib-wiki"
          library={confluenceLibrary}
        />,
      )
      await screen.findByLabelText(/Spaces suchen und auswählen/)

      await user.type(screen.getByLabelText(/Neues Personal Access Token/), 'neues-pat')
      await user.click(screen.getByRole('button', { name: 'Speichern' }))

      expect(
        screen.getByText(/Bitte die Zugangsdaten mit „Verbindung testen“ prüfen/),
      ).toBeInTheDocument()
      expect(mockUpdateLibrary).not.toHaveBeenCalled()
    }, 10000)

    it('names the missing e-mail address when a new Cloud token is typed instead of silently disabling the test', async () => {
      const user = userEvent.setup()
      renderWithProviders(
        <EditLibrarySourceDialog
          open
          onClose={() => {}}
          libraryId="lib-wiki"
          library={{
            ...confluenceLibrary,
            sourceUrl: 'https://behoerde.atlassian.net',
            confluenceEdition: 'CLOUD',
          }}
        />,
      )
      await screen.findByLabelText(/Spaces suchen und auswählen/)
      expect(screen.getByRole('button', { name: 'Verbindung testen' })).toBeEnabled()

      await user.type(screen.getByLabelText(/Neues API-Token/), 'tok-neu')
      expect(
        screen.getByText(/Bei einem neuen API-Token bitte auch die E-Mail-Adresse/),
      ).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Verbindung testen' })).toBeDisabled()

      await user.type(screen.getByLabelText(/E-Mail-Adresse/), 'dienst@behoerde.example')
      expect(screen.getByRole('button', { name: 'Verbindung testen' })).toBeEnabled()
    }, 15000)

    it('tells that the stored token does not follow a host change and stops relying on it', async () => {
      const user = userEvent.setup()
      renderWithProviders(
        <EditLibrarySourceDialog
          open
          onClose={() => {}}
          libraryId="lib-wiki"
          library={confluenceLibrary}
        />,
      )
      await screen.findByLabelText(/Spaces suchen und auswählen/)
      expect(mockListConfluenceSpaces).toHaveBeenCalledTimes(1)

      const address = screen.getByLabelText(/Adresse der Confluence-Instanz/)
      await user.clear(address)
      await user.type(address, 'https://anderes-wiki.example/confluence')

      expect(screen.getByText(/zeigt auf einen anderen Server/)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Verbindung testen' })).toBeDisabled()
      expect(mockListConfluenceSpaces).toHaveBeenCalledTimes(1)
      await user.click(screen.getByRole('button', { name: 'Speichern' }))
      expect(
        screen.getByText(/Bitte die Zugangsdaten mit „Verbindung testen“ prüfen/),
      ).toBeInTheDocument()
      expect(mockUpdateLibrary).not.toHaveBeenCalled()
    }, 15000)

    it('keeps the curated selection when the proxy changes and only withdraws the verification', async () => {
      const user = userEvent.setup()
      renderWithProviders(
        <EditLibrarySourceDialog
          open
          onClose={() => {}}
          libraryId="lib-wiki"
          library={confluenceLibrary}
        />,
      )
      await screen.findByLabelText(/Spaces suchen und auswählen/)

      await user.type(screen.getByLabelText(/Proxy/), 'proxy.example:3128')

      expect(screen.queryByLabelText(/Spaces suchen und auswählen/)).not.toBeInTheDocument()
      expect(screen.getByText(/Die bisherige Auswahl \(BAU\) bleibt bestehen/)).toBeInTheDocument()
    }, 15000)
  })
})
