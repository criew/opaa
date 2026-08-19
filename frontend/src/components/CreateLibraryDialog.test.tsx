import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import CreateLibraryDialog from './CreateLibraryDialog'
import { useLibraryStore } from '../stores/libraryStore'
import { allDocumentSourceTypes, documentSourceTypeConfigKind } from '../utils/labels'
import type {
  GroupListResponse,
  LibraryRequest,
  LibraryResponse,
  SourceConnectionTestResponse,
} from '../types/api'

const { mockCreateLibrary, mockGetMyGroups, mockTestLibrarySource } = vi.hoisted(() => ({
  mockCreateLibrary: vi.fn(async (request: LibraryRequest) => {
    return {
      id: `library-${request.name}`,
      name: request.name,
      description: request.description ?? null,
      ownerType: request.ownerType ?? 'USER',
      ownerId: 'mock-user-id',
      visibility: request.visibility ?? 'PRIVATE',
      listed: request.listed ?? false,
      personal: false,
      myRole: 'OWNER',
      documentCount: 0,
      sourceType: request.sourceType,
      sourcePath: request.sourcePath ?? null,
      sourceUrl: request.sourceUrl ?? null,
      sourceProxy: request.sourceProxy ?? null,
      sourceInsecureSsl: request.sourceInsecureSsl ?? null,
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-01T10:00:00Z',
    } as LibraryResponse
  }),
  mockGetMyGroups: vi.fn(async () => [] as GroupListResponse[]),
  mockTestLibrarySource: vi.fn(
    async () =>
      ({
        reachable: true,
        documentCount: 3,
        message: 'Verzeichnis erreichbar, 3 Dokumente gefunden.',
      }) as SourceConnectionTestResponse,
  ),
}))

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api')
  return {
    ...actual,
    getMyGroups: mockGetMyGroups,
    createLibrary: mockCreateLibrary,
    getLibraries: vi.fn(async () => []),
    testLibrarySource: mockTestLibrarySource,
  }
})

describe('CreateLibraryDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useLibraryStore.setState({ libraries: [], libraryDetails: {}, isLoading: false, error: null })
  })

  it('offers a template for every source type known to the API, each with a description', async () => {
    renderWithProviders(<CreateLibraryDialog open onClose={vi.fn()} onCreated={vi.fn()} />)

    expect(await screen.findByRole('radio', { name: /hochgeladen/i })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /dateisystem/i })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /webverzeichnis/i })).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: /rss-feed/i })).toBeInTheDocument()
    expect(screen.getByText(/dokumente werden manuell hochgeladen/i)).toBeInTheDocument()
  })

  it('creates an UPLOAD library without any source configuration fields', async () => {
    const onCreated = vi.fn()
    renderWithProviders(<CreateLibraryDialog open onClose={vi.fn()} onCreated={onCreated} />)
    const user = userEvent.setup()

    expect(screen.queryByLabelText(/verzeichnispfad/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/adresse \(url\)/i)).not.toBeInTheDocument()

    await user.type(screen.getByLabelText(/^name/i), 'Hochgeladene Dokumente')
    await user.click(screen.getByRole('button', { name: /^erstellen$/i }))

    await waitFor(() => {
      expect(mockCreateLibrary).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'Hochgeladene Dokumente',
          sourceType: 'UPLOAD',
          sourcePath: undefined,
          sourceUrl: undefined,
        }),
      )
    })
    expect(onCreated).toHaveBeenCalledWith('library-Hochgeladene Dokumente')
  })

  it('requires an absolute directory path for the FILESYSTEM template', async () => {
    renderWithProviders(<CreateLibraryDialog open onClose={vi.fn()} onCreated={vi.fn()} />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('radio', { name: /dateisystem/i }))
    await user.type(screen.getByLabelText(/^name/i), 'Serververzeichnis')
    await user.type(screen.getByLabelText(/verzeichnispfad/i), 'relativ/pfad')
    await user.click(screen.getByRole('button', { name: /^erstellen$/i }))

    expect(
      await screen.findByText(/absoluter pfad sein, z\. b\. \/data\/dokumente/i),
    ).toBeInTheDocument()
    expect(mockCreateLibrary).not.toHaveBeenCalled()
  })

  it('creates a FILESYSTEM library with the configured directory path', async () => {
    renderWithProviders(<CreateLibraryDialog open onClose={vi.fn()} onCreated={vi.fn()} />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('radio', { name: /dateisystem/i }))
    await user.type(screen.getByLabelText(/^name/i), 'Serververzeichnis')
    await user.type(screen.getByLabelText(/verzeichnispfad/i), '/data/dokumente')
    await user.click(screen.getByRole('button', { name: /^erstellen$/i }))

    await waitFor(() => {
      expect(mockCreateLibrary).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'Serververzeichnis',
          sourceType: 'FILESYSTEM',
          sourcePath: '/data/dokumente',
        }),
      )
    })
  })

  it('requires an http(s) URL for the HTTP_DIRECTORY template', async () => {
    renderWithProviders(<CreateLibraryDialog open onClose={vi.fn()} onCreated={vi.fn()} />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('radio', { name: /webverzeichnis/i }))
    await user.type(screen.getByLabelText(/^name/i), 'Webverzeichnis')
    await user.type(screen.getByLabelText(/adresse \(url\)/i), 'ftp://files.example.com')
    await user.click(screen.getByRole('button', { name: /^erstellen$/i }))

    expect(
      await screen.findByText(/muss mit http:\/\/ oder https:\/\/ beginnen/i),
    ).toBeInTheDocument()
    expect(mockCreateLibrary).not.toHaveBeenCalled()
  })

  // Types into five fields plus two clicks under userEvent's real-timer typing - already close to
  // the 5s default vitest timeout, so an explicit one keeps this test from flaking under CI load
  // rather than papering over it silently.
  it('creates an HTTP_DIRECTORY library with proxy, credentials and the SSL switch', async () => {
    renderWithProviders(<CreateLibraryDialog open onClose={vi.fn()} onCreated={vi.fn()} />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('radio', { name: /webverzeichnis/i }))
    await user.type(screen.getByLabelText(/^name/i), 'Webverzeichnis')
    await user.type(
      screen.getByLabelText(/adresse \(url\)/i),
      'https://files.example.com/dokumente/',
    )
    await user.type(screen.getByLabelText(/^proxy/i), 'proxy.example.com:8080')
    await user.type(screen.getByLabelText(/anmeldedaten/i), 'admin:secret')
    await user.click(screen.getByRole('switch', { name: /zertifikatsprüfung aussetzen/i }))
    await user.click(screen.getByRole('button', { name: /^erstellen$/i }))

    await waitFor(() => {
      expect(mockCreateLibrary).toHaveBeenCalledWith(
        expect.objectContaining({
          name: 'Webverzeichnis',
          sourceType: 'HTTP_DIRECTORY',
          sourceUrl: 'https://files.example.com/dokumente/',
          sourceProxy: 'proxy.example.com:8080',
          sourceCredentials: 'admin:secret',
          sourceInsecureSsl: true,
        }),
      )
    })
  }, 10000)

  it('shows a hint that OPAA fetches feed-linked pages for the RSS_FEED template', async () => {
    renderWithProviders(<CreateLibraryDialog open onClose={vi.fn()} onCreated={vi.fn()} />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('radio', { name: /rss-feed/i }))

    expect(
      await screen.findByText(/ruft neben dem feed auch die von ihm verlinkten detailseiten ab/i),
    ).toBeInTheDocument()
  })

  it('maps every known source type to a config kind, so a future type cannot silently render no fields', () => {
    for (const type of allDocumentSourceTypes) {
      expect(['none', 'path', 'url']).toContain(documentSourceTypeConfigKind[type])
    }
    // UPLOAD stays field-less by design, and both URL-based types share the identical shape.
    expect(documentSourceTypeConfigKind.UPLOAD).toBe('none')
    expect(documentSourceTypeConfigKind.FILESYSTEM).toBe('path')
    expect(documentSourceTypeConfigKind.HTTP_DIRECTORY).toBe('url')
    expect(documentSourceTypeConfigKind.RSS_FEED).toBe('url')
  })

  it('keeps the credentials and proxy fields out of the browser password manager', async () => {
    renderWithProviders(<CreateLibraryDialog open onClose={vi.fn()} onCreated={vi.fn()} />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('radio', { name: /webverzeichnis/i }))

    expect(screen.getByLabelText(/anmeldedaten/i)).toHaveAttribute('autocomplete', 'new-password')
    expect(screen.getByLabelText(/^proxy/i)).toHaveAttribute('autocomplete', 'off')
  })

  it('shows a German backend validation error without losing the entered data', async () => {
    mockCreateLibrary.mockRejectedValueOnce(new Error('sourcePath muss ein absoluter Pfad sein'))
    renderWithProviders(<CreateLibraryDialog open onClose={vi.fn()} onCreated={vi.fn()} />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('radio', { name: /dateisystem/i }))
    await user.type(screen.getByLabelText(/^name/i), 'Serververzeichnis')
    await user.type(screen.getByLabelText(/verzeichnispfad/i), '/data/dokumente')
    await user.click(screen.getByRole('button', { name: /^erstellen$/i }))

    expect(await screen.findByText('sourcePath muss ein absoluter Pfad sein')).toBeInTheDocument()
    expect(screen.getByLabelText(/verzeichnispfad/i)).toHaveValue('/data/dokumente')
  })

  describe('Verbindung testen (#514)', () => {
    it('offers no test button for the UPLOAD template, which has nothing to test', async () => {
      renderWithProviders(<CreateLibraryDialog open onClose={vi.fn()} onCreated={vi.fn()} />)

      expect(await screen.findByRole('radio', { name: /hochgeladen/i })).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /verbindung testen/i })).not.toBeInTheDocument()
    })

    it('reports a successful FILESYSTEM test inline, with the document count from the backend', async () => {
      renderWithProviders(<CreateLibraryDialog open onClose={vi.fn()} onCreated={vi.fn()} />)
      const user = userEvent.setup()

      await user.click(screen.getByRole('radio', { name: /dateisystem/i }))
      await user.type(screen.getByLabelText(/verzeichnispfad/i), '/data/dokumente')
      await user.click(screen.getByRole('button', { name: /verbindung testen/i }))

      expect(
        await screen.findByText('Verzeichnis erreichbar, 3 Dokumente gefunden.'),
      ).toBeInTheDocument()
      expect(mockTestLibrarySource).toHaveBeenCalledWith(
        expect.objectContaining({ sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
      )
      // The test is purely diagnostic - creating the library is still a separate, explicit step.
      expect(mockCreateLibrary).not.toHaveBeenCalled()
    })

    it('shows an unreachable result as a warning, not an error, since the test itself succeeded', async () => {
      mockTestLibrarySource.mockResolvedValueOnce({
        reachable: false,
        documentCount: null,
        message: 'Das Verzeichnis existiert nicht.',
      })
      renderWithProviders(<CreateLibraryDialog open onClose={vi.fn()} onCreated={vi.fn()} />)
      const user = userEvent.setup()

      await user.click(screen.getByRole('radio', { name: /dateisystem/i }))
      await user.type(screen.getByLabelText(/verzeichnispfad/i), '/data/nicht-vorhanden')
      await user.click(screen.getByRole('button', { name: /verbindung testen/i }))

      const alert = await screen.findByText('Das Verzeichnis existiert nicht.')
      expect(alert.closest('[class*="colorWarning"]')).not.toBeNull()
    })

    it('shows a German backend error when the test call itself fails', async () => {
      mockTestLibrarySource.mockRejectedValueOnce(
        new Error('sourcePath liegt ausserhalb der freigegebenen Verzeichnisse'),
      )
      renderWithProviders(<CreateLibraryDialog open onClose={vi.fn()} onCreated={vi.fn()} />)
      const user = userEvent.setup()

      await user.click(screen.getByRole('radio', { name: /dateisystem/i }))
      await user.type(screen.getByLabelText(/verzeichnispfad/i), '/etc/shadow')
      await user.click(screen.getByRole('button', { name: /verbindung testen/i }))

      expect(
        await screen.findByText('sourcePath liegt ausserhalb der freigegebenen Verzeichnisse'),
      ).toBeInTheDocument()
    })

    it('does not require a directory path before validating the FILESYSTEM template itself', async () => {
      renderWithProviders(<CreateLibraryDialog open onClose={vi.fn()} onCreated={vi.fn()} />)
      const user = userEvent.setup()

      await user.click(screen.getByRole('radio', { name: /dateisystem/i }))
      await user.click(screen.getByRole('button', { name: /verbindung testen/i }))

      expect(await screen.findByText('Verzeichnispfad ist erforderlich')).toBeInTheDocument()
      expect(mockTestLibrarySource).not.toHaveBeenCalled()
    })

    it('sends proxy, credentials and the SSL switch for an HTTP_DIRECTORY test', async () => {
      renderWithProviders(<CreateLibraryDialog open onClose={vi.fn()} onCreated={vi.fn()} />)
      const user = userEvent.setup()

      await user.click(screen.getByRole('radio', { name: /webverzeichnis/i }))
      await user.type(
        screen.getByLabelText(/adresse \(url\)/i),
        'https://files.example.com/dokumente/',
      )
      await user.type(screen.getByLabelText(/^proxy/i), 'proxy.example.com:8080')
      await user.type(screen.getByLabelText(/anmeldedaten/i), 'admin:secret')
      await user.click(screen.getByRole('switch', { name: /zertifikatsprüfung aussetzen/i }))
      await user.click(screen.getByRole('button', { name: /verbindung testen/i }))

      await waitFor(() => {
        expect(mockTestLibrarySource).toHaveBeenCalledWith(
          expect.objectContaining({
            sourceType: 'HTTP_DIRECTORY',
            sourceUrl: 'https://files.example.com/dokumente/',
            sourceProxy: 'proxy.example.com:8080',
            sourceCredentials: 'admin:secret',
            sourceInsecureSsl: true,
          }),
        )
      })
    }, 10000)

    it('clears a previous test result once the source type template is changed', async () => {
      renderWithProviders(<CreateLibraryDialog open onClose={vi.fn()} onCreated={vi.fn()} />)
      const user = userEvent.setup()

      await user.click(screen.getByRole('radio', { name: /dateisystem/i }))
      await user.type(screen.getByLabelText(/verzeichnispfad/i), '/data/dokumente')
      await user.click(screen.getByRole('button', { name: /verbindung testen/i }))
      expect(
        await screen.findByText('Verzeichnis erreichbar, 3 Dokumente gefunden.'),
      ).toBeInTheDocument()

      await user.click(screen.getByRole('radio', { name: /webverzeichnis/i }))

      expect(
        screen.queryByText('Verzeichnis erreichbar, 3 Dokumente gefunden.'),
      ).not.toBeInTheDocument()
    })
  })
})
