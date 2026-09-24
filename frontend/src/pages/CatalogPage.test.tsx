import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { renderWithProviders, setMockAuthState } from '../test/test-utils'
import { server } from '../mocks/server'
import type { CatalogEntryResponse } from '../types/api'
import { useCatalogStore } from '../stores/catalogStore'
import CatalogPage from './CatalogPage'

const requestedUrls: URL[] = []

function recordCatalogRequests({ request }: { request: Request }) {
  const url = new URL(request.url)
  if (url.pathname === '/api/v1/catalog') requestedUrls.push(url)
}

function entry(name: string, overrides: Partial<CatalogEntryResponse> = {}): CatalogEntryResponse {
  return {
    assetType: 'PROMPT_LIBRARY',
    assetId: name,
    name,
    description: null,
    ownerType: 'USER',
    ownerLabel: 'Dana Beispiel',
    origin: 'LOCAL',
    accessible: true,
    listed: true,
    succession: null,
    ...overrides,
  }
}

describe('CatalogPage (#1904)', () => {
  beforeEach(() => {
    setMockAuthState()
    useCatalogStore.getState().reset()
    requestedUrls.length = 0
    server.events.on('request:start', recordCatalogRequests)
    window.localStorage.removeItem('opaa.overview.catalog.view')
  })

  afterEach(() => {
    server.events.removeListener('request:start', recordCatalogRequests)
  })

  it('mixes both asset types and links the accessible entries to their own pages', async () => {
    renderWithProviders(<CatalogPage />, { withRouter: true })

    expect(screen.getByRole('heading', { level: 1, name: 'Katalog' })).toBeInTheDocument()
    const knowledge = await screen.findByRole('link', { name: /Rechtsquellen Soziales/ })
    expect(knowledge).toHaveAttribute('href', '/libraries/library-referat-50')
    expect(within(knowledge).getByText('Wissensbibliothek')).toBeInTheDocument()
    const prompts = screen.getByRole('link', { name: /Formulierungshilfen Referat 50/ })
    expect(prompts).toHaveAttribute('href', '/prompts/prompt-library-referat-50')
    expect(within(prompts).getByText('Prompt-Bibliothek')).toBeInTheDocument()
    expect(within(prompts).getByText('zuständig: Referat 50')).toBeInTheDocument()
  })

  it('shows a listed entry without access with its responsible party, but not as a link', async () => {
    renderWithProviders(<CatalogPage />, { withRouter: true })

    const listed = await screen.findByRole('article', { name: 'Satzungen der Kämmerei' })
    expect(
      within(listed).getByText('Auffindbar ohne Berechtigung — zuständig: Kämmerei'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Satzungen der Kämmerei/ })).not.toBeInTheDocument()
    // While the succession is open, its addressee is the one to turn to.
    const orphaned = screen.getByRole('article', { name: 'Bescheidbausteine Ordnungsamt' })
    expect(
      within(orphaned).getByText('Auffindbar ohne Berechtigung — zuständig: die Systemverwaltung'),
    ).toBeInTheDocument()
  })

  it('filters by type on the server', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CatalogPage />, { withRouter: true })
    await screen.findByRole('link', { name: /Rechtsquellen Soziales/ })

    await user.click(screen.getByRole('button', { name: 'Prompts' }))

    await waitFor(() =>
      expect(
        screen.queryByRole('link', { name: /Rechtsquellen Soziales/ }),
      ).not.toBeInTheDocument(),
    )
    expect(screen.getByRole('link', { name: /Formulierungshilfen Referat 50/ })).toBeInTheDocument()
    expect(
      screen.getByRole('article', { name: 'Bescheidbausteine Ordnungsamt' }),
    ).toBeInTheDocument()
    expect(requestedUrls.at(-1)?.searchParams.get('type')).toBe('PROMPT_LIBRARY')
  })

  it('searches on the server and keeps the search in reach when nothing matches', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CatalogPage />, { withRouter: true })
    await screen.findByRole('link', { name: /Rechtsquellen Soziales/ })

    await user.type(screen.getByRole('textbox', { name: 'Suchen' }), 'gebühren')

    expect(
      await screen.findByRole('article', { name: 'Satzungen der Kämmerei' }),
    ).toBeInTheDocument()
    await waitFor(() =>
      expect(
        screen.queryByRole('link', { name: /Rechtsquellen Soziales/ }),
      ).not.toBeInTheDocument(),
    )
    expect(requestedUrls.at(-1)?.searchParams.get('q')).toBe('gebühren')
    expect(screen.getByText('1 Eintrag')).toBeInTheDocument()

    await user.clear(screen.getByRole('textbox', { name: 'Suchen' }))
    await user.type(screen.getByRole('textbox', { name: 'Suchen' }), 'nichts dergleichen')

    expect(
      await screen.findByText('Kein Eintrag passt zu „nichts dergleichen“.', { selector: 'p' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Suchen' })).toBeInTheDocument()
  })

  it('loads further pages on request and names the total', async () => {
    server.use(
      http.get('/api/v1/catalog', ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page'))
        return HttpResponse.json({
          entries: [entry(page === 0 ? 'Erste Seite' : 'Zweite Seite')],
          page,
          size: 50,
          totalElements: 51,
          totalPages: 2,
        })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<CatalogPage />, { withRouter: true })

    expect(await screen.findByRole('link', { name: /Erste Seite/ })).toBeInTheDocument()
    expect(screen.getByText('51 Einträge')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Weitere laden' }))

    expect(await screen.findByRole('link', { name: /Zweite Seite/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Erste Seite/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Weitere laden' })).not.toBeInTheDocument()
  })

  it('names type, responsible party and origin in the table', async () => {
    const user = userEvent.setup()
    renderWithProviders(<CatalogPage />, { withRouter: true })
    await screen.findByRole('link', { name: /Rechtsquellen Soziales/ })

    await user.click(screen.getByRole('button', { name: 'Tabelle' }))

    const row = screen.getByRole('row', { name: /Satzungen der Kämmerei/ })
    expect(within(row).getByText('Wissensbibliothek')).toBeInTheDocument()
    expect(within(row).getByText('Kämmerei')).toBeInTheDocument()
    expect(within(row).getByText('lokal angelegt')).toBeInTheDocument()
    expect(
      within(row).getByText('Auffindbar ohne Berechtigung — zuständig: Kämmerei'),
    ).toBeInTheDocument()
    expect(within(row).queryByRole('link')).not.toBeInTheDocument()
  })
})
