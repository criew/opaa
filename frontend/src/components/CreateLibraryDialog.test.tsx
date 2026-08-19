import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import CreateLibraryDialog from './CreateLibraryDialog'
import { useLibraryStore } from '../stores/libraryStore'
import type { GroupListResponse, LibraryRequest, LibraryResponse } from '../types/api'

const { mockCreateLibrary, mockGetMyGroups } = vi.hoisted(() => ({
  mockCreateLibrary: vi.fn(async (request: LibraryRequest) => {
    return {
      id: `library-${request.name}`,
      name: request.name,
      description: request.description ?? null,
      ownerType: request.ownerType ?? 'USER',
      ownerId: null,
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
}))

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api')
  return {
    ...actual,
    getMyGroups: mockGetMyGroups,
    createLibrary: mockCreateLibrary,
    getLibraries: vi.fn(async () => []),
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
    expect(screen.getByRole('radio', { name: /verzeichnisliste/i })).toBeInTheDocument()
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

    await user.click(screen.getByRole('radio', { name: /verzeichnisliste/i }))
    await user.type(screen.getByLabelText(/^name/i), 'Verzeichnisliste')
    await user.type(screen.getByLabelText(/adresse \(url\)/i), 'ftp://files.example.com')
    await user.click(screen.getByRole('button', { name: /^erstellen$/i }))

    expect(
      await screen.findByText(/muss mit http:\/\/ oder https:\/\/ beginnen/i),
    ).toBeInTheDocument()
    expect(mockCreateLibrary).not.toHaveBeenCalled()
  })

  it('creates an HTTP_DIRECTORY library with proxy, credentials and the SSL switch', async () => {
    renderWithProviders(<CreateLibraryDialog open onClose={vi.fn()} onCreated={vi.fn()} />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('radio', { name: /verzeichnisliste/i }))
    await user.type(screen.getByLabelText(/^name/i), 'Verzeichnisliste')
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
          name: 'Verzeichnisliste',
          sourceType: 'HTTP_DIRECTORY',
          sourceUrl: 'https://files.example.com/dokumente/',
          sourceProxy: 'proxy.example.com:8080',
          sourceCredentials: 'admin:secret',
          sourceInsecureSsl: true,
        }),
      )
    })
  })

  it('shows a hint that OPAA fetches feed-linked pages for the RSS_FEED template', async () => {
    renderWithProviders(<CreateLibraryDialog open onClose={vi.fn()} onCreated={vi.fn()} />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('radio', { name: /rss-feed/i }))

    expect(
      await screen.findByText(/ruft neben dem feed auch die von ihm verlinkten detailseiten ab/i),
    ).toBeInTheDocument()
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
})
