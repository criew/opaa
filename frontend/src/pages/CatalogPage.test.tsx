import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { Route, Routes, useLocation } from 'react-router'
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
    ownerId: 'user-dana',
    ownerLabel: 'Dana Beispiel',
    origin: 'LOCAL',
    visibility: 'RESTRICTED',
    myRole: 'VIEWER',
    status: 'READY',
    updatedAt: '2026-09-30T08:00:00Z',
    itemCount: 3,
    spaceCount: 1,
    succession: null,
    ...overrides,
  } as CatalogEntryResponse
}

function LocationProbe() {
  const location = useLocation()
  return <div data-testid="location">{location.pathname + location.search}</div>
}

function renderCatalog(initialRoute = '/catalog') {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/catalog" element={<CatalogPage />} />
        <Route path="/catalog/new" element={<div>Typwahl</div>} />
      </Routes>
      <LocationProbe />
    </>,
    { withRouter: true, initialRoute },
  )
}

describe('CatalogPage (ADR-0039)', () => {
  beforeEach(() => {
    setMockAuthState()
    useCatalogStore.getState().reset()
    requestedUrls.length = 0
    server.events.on('request:start', recordCatalogRequests)
  })

  afterEach(() => {
    server.events.removeListener('request:start', recordCatalogRequests)
  })

  it('mixes both asset types as cards with type badge, extent and responsible party', async () => {
    renderCatalog()

    expect(screen.getByRole('heading', { level: 1, name: 'Katalog' })).toBeInTheDocument()
    const knowledge = await screen.findByRole('link', { name: /Rechtsquellen Soziales/ })
    expect(knowledge).toHaveAttribute('href', '/libraries/library-referat-50')
    expect(within(knowledge).getByText('Wissensbibliothek')).toBeInTheDocument()
    const prompts = screen.getByRole('link', { name: /Formulierungshilfen Referat 50/ })
    expect(prompts).toHaveAttribute('href', '/prompts/prompt-library-referat-50')
    expect(within(prompts).getByText('Prompt-Bibliothek')).toBeInTheDocument()
    expect(within(prompts).getByText('zuständig: Referat 50')).toBeInTheDocument()
  })

  it('offers only cards - no table and no switch to one', async () => {
    renderCatalog()
    await screen.findByRole('link', { name: /Rechtsquellen Soziales/ })

    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Tabelle' })).not.toBeInTheDocument()
  })

  it('filters by type on the server and keeps the filter in the address', async () => {
    const user = userEvent.setup()
    renderCatalog()
    await screen.findByRole('link', { name: /Rechtsquellen Soziales/ })

    await user.click(screen.getByRole('button', { name: 'Prompts' }))

    await waitFor(() =>
      expect(
        screen.queryByRole('link', { name: /Rechtsquellen Soziales/ }),
      ).not.toBeInTheDocument(),
    )
    expect(screen.getByRole('link', { name: /Formulierungshilfen Referat 50/ })).toBeInTheDocument()
    expect(requestedUrls.at(-1)?.searchParams.get('type')).toBe('PROMPT_LIBRARY')
    expect(screen.getByTestId('location')).toHaveTextContent('/catalog?type=prompts')

    await user.click(
      within(screen.getByRole('group', { name: 'Typ' })).getByRole('button', { name: 'Alle' }),
    )
    expect(await screen.findByRole('link', { name: /Rechtsquellen Soziales/ })).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/catalog$/)
  })

  // /libraries and /prompts lead here with the type preselected.
  it('starts narrowed to the type the address names', async () => {
    renderCatalog('/catalog?type=knowledge')

    expect(await screen.findByRole('link', { name: /Rechtsquellen Soziales/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Wissen' })).toHaveAttribute('aria-pressed', 'true')
    expect(requestedUrls.every((url) => url.searchParams.get('type') === 'KNOWLEDGE_LIBRARY')).toBe(
      true,
    )
    expect(
      screen.queryByRole('link', { name: /Formulierungshilfen Referat 50/ }),
    ).not.toBeInTheDocument()
  })

  it('searches on the server and keeps the search in reach when nothing matches', async () => {
    const user = userEvent.setup()
    renderCatalog()
    await screen.findByRole('link', { name: /Rechtsquellen Soziales/ })

    await user.type(screen.getByRole('textbox', { name: 'Suchen' }), 'nichts dergleichen')

    expect(
      await screen.findByText('Kein Eintrag passt zu „nichts dergleichen“.', { selector: 'p' }),
    ).toBeInTheDocument()
    expect(requestedUrls.at(-1)?.searchParams.get('q')).toBe('nichts dergleichen')
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
    renderCatalog()

    expect(await screen.findByRole('link', { name: /Erste Seite/ })).toBeInTheDocument()
    expect(screen.getByText('51 Einträge')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Weitere laden' }))

    expect(await screen.findByRole('link', { name: /Zweite Seite/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Erste Seite/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Weitere laden' })).not.toBeInTheDocument()
  })

  it('names the open succession and its addressee on the card', async () => {
    server.use(
      http.get('/api/v1/catalog', () =>
        HttpResponse.json({
          entries: [
            entry('Verwaiste Bausteine', {
              succession: {
                addressee: 'SYSTEM_ADMINISTRATION',
                addresseeLabel: 'die Systemverwaltung',
              },
            }),
          ],
          page: 0,
          size: 50,
          totalElements: 1,
          totalPages: 1,
        }),
      ),
    )
    renderCatalog()

    const card = await screen.findByRole('link', { name: /Verwaiste Bausteine/ })
    expect(within(card).getByText('zuständig: die Systemverwaltung')).toBeInTheDocument()
    expect(within(card).getByText('3 Prompts · in 1 Space')).toBeInTheDocument()
  })

  it('leads "Neu" to the type choice', async () => {
    const user = userEvent.setup()
    renderCatalog()

    await user.click(await screen.findByRole('button', { name: 'Neu' }))

    expect(await screen.findByText('Typwahl')).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/catalog/new')
  })

  it('offers no "Neu" to a person without any creation right', async () => {
    server.use(http.get('/api/v1/me/capabilities', () => HttpResponse.json({ capabilities: [] })))
    renderCatalog()
    await screen.findByRole('link', { name: /Rechtsquellen Soziales/ })

    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Neu' })).not.toBeInTheDocument(),
    )
  })

  describe('tile fields, filters and sort (#2093)', () => {
    function serve(entries: CatalogEntryResponse[]) {
      server.use(
        http.get('/api/v1/catalog', () =>
          HttpResponse.json({
            entries,
            page: 0,
            size: 50,
            totalElements: entries.length,
            totalPages: 1,
          }),
        ),
      )
    }

    it('shows visibility, own role and the Stand of a ready entry', async () => {
      serve([
        entry('Bauordnung', {
          assetType: 'KNOWLEDGE_LIBRARY',
          visibility: 'PUBLIC',
          myRole: 'MANAGER',
          knowledgeLibrary: {
            sourceType: 'FILESYSTEM',
            indexingStatus: 'READY',
            lastIndexedAt: '2026-08-18T06:00:00Z',
          },
        }),
        entry('Bescheidbausteine', { updatedAt: '2026-09-30T08:00:00Z' }),
      ])
      renderCatalog()

      const knowledge = await screen.findByRole('link', { name: /Bauordnung/ })
      expect(within(knowledge).getByText('Für alle')).toBeInTheDocument()
      expect(within(knowledge).getByText('Verwalter')).toBeInTheDocument()
      expect(within(knowledge).getByText('Stand 18.08.2026')).toBeInTheDocument()
      const prompts = screen.getByRole('link', { name: /Bescheidbausteine/ })
      expect(within(prompts).getByText('Eingeschränkt')).toBeInTheDocument()
      expect(within(prompts).getByText('Leser')).toBeInTheDocument()
      // A type without a measure of its own is READY; its Stand is the last change.
      expect(within(prompts).getByText('Stand 30.09.2026')).toBeInTheDocument()
      // Both words are explained on the page, not only behind a tooltip.
      expect(screen.getByText(/„Für alle“: an alle Konten freigegeben/)).toBeInTheDocument()
    })

    // An upload library has no runs and so no lastIndexedAt; its Stand is its last change.
    it('gives a ready upload library its last change as Stand', async () => {
      serve([
        entry('Hochgeladenes', {
          assetType: 'KNOWLEDGE_LIBRARY',
          updatedAt: '2026-09-12T08:00:00Z',
          knowledgeLibrary: { sourceType: 'UPLOAD', indexingStatus: 'READY' },
        }),
      ])
      renderCatalog()

      const card = await screen.findByRole('link', { name: /Hochgeladenes/ })
      expect(within(card).getByText('Stand 12.09.2026')).toBeInTheDocument()
    })

    it('titles every filter group and the sort visibly, and names them by that title', async () => {
      renderCatalog()
      await screen.findByRole('link', { name: /Rechtsquellen Soziales/ })

      for (const title of ['Typ', 'Sichtbarkeit', 'Sortierung']) {
        const group = screen.getByRole('group', { name: title })
        const labelId = group.getAttribute('aria-labelledby')
        expect(labelId).toBeTruthy()
        expect(document.getElementById(labelId!)).toHaveTextContent(title)
        expect(document.getElementById(labelId!)).toBeVisible()
      }
    })

    it('names a state that is not ready in words, and keeps it visible under an open succession', async () => {
      serve([
        entry('Fehlgeschlagen', {
          assetType: 'KNOWLEDGE_LIBRARY',
          status: 'UPDATE_FAILED',
          knowledgeLibrary: { sourceType: 'WEB', indexingStatus: 'UPDATE_FAILED' },
        }),
        entry('Verwaist', {
          assetType: 'KNOWLEDGE_LIBRARY',
          status: 'SUCCESSION_OPEN',
          succession: {
            addressee: 'SYSTEM_ADMINISTRATION',
            addresseeLabel: 'die Systemverwaltung',
          },
          knowledgeLibrary: { sourceType: 'UPLOAD', indexingStatus: 'UPDATING' },
        }),
        entry('Leer', {
          assetType: 'KNOWLEDGE_LIBRARY',
          status: 'NOT_YET_AVAILABLE',
          knowledgeLibrary: { sourceType: 'UPLOAD', indexingStatus: 'NOT_YET_AVAILABLE' },
        }),
      ])
      renderCatalog()

      const failed = await screen.findByRole('link', { name: /Fehlgeschlagen/ })
      expect(within(failed).getByText('Aktualisierung fehlgeschlagen')).toBeInTheDocument()
      const orphaned = screen.getByRole('link', { name: /Verwaist/ })
      expect(within(orphaned).getByText('Wird aktualisiert')).toBeInTheDocument()
      expect(within(orphaned).getByText(/Nachfolge offen/)).toBeInTheDocument()
      const empty = screen.getByRole('link', { name: /Leer/ })
      expect(within(empty).getByText('Noch kein Inhalt')).toBeInTheDocument()
    })

    it('filters by visibility on the server and keeps it in the address', async () => {
      const user = userEvent.setup()
      renderCatalog()
      await screen.findByRole('link', { name: /Rechtsquellen Soziales/ })

      const visibility = screen.getByRole('group', { name: 'Sichtbarkeit' })
      await user.click(within(visibility).getByRole('button', { name: 'Eingeschränkt' }))

      await waitFor(() =>
        expect(requestedUrls.at(-1)?.searchParams.get('visibility')).toBe('RESTRICTED'),
      )
      expect(screen.getByTestId('location')).toHaveTextContent('visibility=restricted')
      await waitFor(() =>
        expect(screen.queryByText('Dienstanweisungen', { exact: true })).not.toBeInTheDocument(),
      )
    })

    it('narrows to assets from my groups on the server', async () => {
      const user = userEvent.setup()
      renderCatalog()
      await screen.findByRole('link', { name: /Rechtsquellen Soziales/ })

      await user.click(screen.getByRole('checkbox', { name: 'Aus meinen Gruppen' }))

      await waitFor(() =>
        expect(requestedUrls.at(-1)?.searchParams.get('fromMyGroups')).toBe('true'),
      )
      expect(screen.getByTestId('location')).toHaveTextContent('groups=1')
      await waitFor(() =>
        expect(screen.queryByRole('link', { name: /Meine Dokumente/ })).not.toBeInTheDocument(),
      )
      expect(screen.getByRole('link', { name: /Rechtsquellen Soziales/ })).toBeInTheDocument()
    })

    it('sorts by the last change on the server, by name by default', async () => {
      const user = userEvent.setup()
      renderCatalog()
      await screen.findByRole('link', { name: /Rechtsquellen Soziales/ })
      expect(requestedUrls.at(-1)?.searchParams.get('sort')).toBeNull()

      const sort = screen.getByRole('group', { name: 'Sortierung' })
      expect(within(sort).getByRole('button', { name: 'Name' })).toHaveAttribute(
        'aria-pressed',
        'true',
      )
      await user.click(within(sort).getByRole('button', { name: 'Zuletzt geändert' }))

      await waitFor(() => expect(requestedUrls.at(-1)?.searchParams.get('sort')).toBe('updatedAt'))
      expect(screen.getByTestId('location')).toHaveTextContent('sort=updated')
    })

    it('starts with every filter and the sort the address names', async () => {
      renderCatalog('/catalog?type=knowledge&visibility=public&groups=1&sort=updated')

      await waitFor(() => expect(requestedUrls.length).toBeGreaterThan(0))
      const params = requestedUrls.at(-1)!.searchParams
      expect(params.get('type')).toBe('KNOWLEDGE_LIBRARY')
      expect(params.get('visibility')).toBe('PUBLIC')
      expect(params.get('fromMyGroups')).toBe('true')
      expect(params.get('sort')).toBe('updatedAt')
      expect(screen.getByRole('checkbox', { name: 'Aus meinen Gruppen' })).toBeChecked()
    })
  })
})
