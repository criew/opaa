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
    favorite: false,
    succession: null,
    ...overrides,
  } as CatalogEntryResponse
}

/** The card around a card's link - the link carries only the name, the card everything else. */
function cardOf(link: HTMLElement): HTMLElement {
  const card = link.parentElement
  if (!card) throw new Error('link outside a card')
  return card
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
    expect(within(cardOf(knowledge)).getByText('Wissen')).toBeInTheDocument()
    expect(within(cardOf(knowledge)).queryByText('Wissensbibliothek')).not.toBeInTheDocument()
    const prompts = screen.getByRole('link', { name: /Formulierungshilfen Referat 50/ })
    expect(prompts).toHaveAttribute('href', '/prompts/prompt-library-referat-50')
    expect(within(cardOf(prompts)).getByText('Prompts')).toBeInTheDocument()
    expect(within(cardOf(prompts)).getByText('Referat 50')).toBeInTheDocument()
    expect(within(cardOf(prompts)).getByTitle('Zuständige Gruppe')).toBeInTheDocument()
    expect(within(cardOf(prompts)).queryByText(/zuständig:/)).not.toBeInTheDocument()
  })

  it('says under the heading what the catalog holds, without a footnote', async () => {
    renderCatalog()
    await screen.findByRole('link', { name: /Rechtsquellen Soziales/ })

    expect(
      screen.getByText('Alles, was Sie nutzen dürfen. Ihre Favoriten stehen oben.'),
    ).toBeInTheDocument()
    expect(
      screen.queryByText(/Der Katalog zeigt nur, was Sie lesen dürfen/),
    ).not.toBeInTheDocument()
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

    await user.type(screen.getByRole('searchbox', { name: 'Suchen' }), 'nichts dergleichen')

    expect(
      await screen.findByText('Kein Eintrag passt zu „nichts dergleichen“.', { selector: 'p' }),
    ).toBeInTheDocument()
    expect(requestedUrls.at(-1)?.searchParams.get('q')).toBe('nichts dergleichen')
    expect(screen.getByRole('searchbox', { name: 'Suchen' })).toBeInTheDocument()
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
    expect(screen.getByText('1 von 51 angezeigt')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Weitere laden' }))

    expect(await screen.findByRole('link', { name: /Zweite Seite/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Erste Seite/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Weitere laden' })).not.toBeInTheDocument()
    expect(screen.queryByText(/angezeigt$/)).not.toBeInTheDocument()
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

    const card = cardOf(await screen.findByRole('link', { name: /Verwaiste Bausteine/ }))
    expect(within(card).getByText('die Systemverwaltung')).toBeInTheDocument()
    expect(within(card).getByTitle('Zuständige Gruppe')).toBeInTheDocument()
    expect(within(card).getByText('3 Prompts · in 1 Space')).toBeInTheDocument()
    // "Nachfolge offen" is a state line behind a dot; the addressee stands once, without a prefix.
    const succession = within(card).getByText('Nachfolge offen')
    expect(succession.parentElement?.querySelector('[aria-hidden="true"]')).not.toBeNull()
    expect(within(card).getAllByText('die Systemverwaltung')).toHaveLength(1)
    expect(within(card).queryByText(/zuständig:/)).not.toBeInTheDocument()
  })

  it('marks and unmarks a favorite with a star beside the link, by keyboard', async () => {
    const user = userEvent.setup()
    const favoriteRequests: string[] = []
    const recordFavorite = ({ request }: { request: Request }) => {
      if (new URL(request.url).pathname.endsWith('/favorite')) {
        favoriteRequests.push(`${request.method} ${new URL(request.url).pathname}`)
      }
    }
    let release: () => void = () => {}
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    server.use(
      http.put('/api/v1/assets/:assetType/:assetId/favorite', async () => {
        await gate
        return new HttpResponse(null, { status: 204 })
      }),
    )
    server.events.on('request:start', recordFavorite)
    renderCatalog()

    const link = await screen.findByRole('link', { name: 'Formulierungshilfen Referat 50' })
    expect(within(link).queryByRole('button')).not.toBeInTheDocument()
    const star = within(cardOf(link)).getByRole('button', {
      name: '„Formulierungshilfen Referat 50“ als Favorit markieren',
    })

    star.focus()
    await user.keyboard('{Enter}')
    // pending: only aria-disabled - a natively disabled button would drop the focus
    await waitFor(() => expect(star).toHaveAttribute('aria-disabled', 'true'))
    expect(star).not.toBeDisabled()
    expect(star).toHaveFocus()
    await user.keyboard('{Enter}')
    release()

    const marked = await within(cardOf(link)).findByRole('button', {
      name: '„Formulierungshilfen Referat 50“ aus den Favoriten entfernen',
    })
    expect(marked).toHaveFocus()
    await waitFor(() => expect(marked).not.toHaveAttribute('aria-disabled'))
    await user.keyboard('{Enter}')
    const unmarked = await within(cardOf(link)).findByRole('button', {
      name: '„Formulierungshilfen Referat 50“ als Favorit markieren',
    })
    expect(unmarked).toHaveFocus()

    server.events.removeListener('request:start', recordFavorite)
    expect(favoriteRequests).toEqual([
      'PUT /api/v1/assets/PROMPT_LIBRARY/prompt-library-referat-50/favorite',
      'DELETE /api/v1/assets/PROMPT_LIBRARY/prompt-library-referat-50/favorite',
    ])
  })

  it('loads further pages after a toggle without repeating or skipping an entry', async () => {
    const user = userEvent.setup()
    const names = Array.from({ length: 60 }, (_, i) => `Eintrag ${String(i).padStart(2, '0')}`)
    const favorites = new Set<string>(['Eintrag 55'])
    server.use(
      http.get('/api/v1/catalog', ({ request }) => {
        const params = new URL(request.url).searchParams
        const page = Number(params.get('page') ?? '0')
        const size = Number(params.get('size') ?? '50')
        const all = [...names]
          .sort((a, b) => Number(favorites.has(b)) - Number(favorites.has(a)) || a.localeCompare(b))
          .map((name) => entry(name, { favorite: favorites.has(name) }))
        return HttpResponse.json({
          entries: all.slice(page * size, page * size + size),
          page,
          size,
          totalElements: all.length,
          totalPages: Math.ceil(all.length / size),
        })
      }),
      http.delete('/api/v1/assets/:assetType/:assetId/favorite', ({ params }) => {
        favorites.delete(String(params.assetId))
        return new HttpResponse(null, { status: 204 })
      }),
    )
    renderCatalog()

    const first = await screen.findByRole('link', { name: 'Eintrag 55' })
    await user.click(
      within(cardOf(first)).getByRole('button', {
        name: '„Eintrag 55“ aus den Favoriten entfernen',
      }),
    )
    await within(cardOf(first)).findByRole('button', { name: '„Eintrag 55“ als Favorit markieren' })
    await user.click(screen.getByRole('button', { name: 'Weitere laden' }))

    await waitFor(() => expect(screen.getAllByRole('link', { name: /^Eintrag / })).toHaveLength(60))
    expect(screen.getAllByRole('link', { name: 'Eintrag 55' })).toHaveLength(1)
    expect(screen.getByRole('link', { name: 'Eintrag 49' })).toBeInTheDocument()
  })

  it('narrows to the own favorites with the "Favoriten" chip', async () => {
    const user = userEvent.setup()
    renderCatalog()
    const link = await screen.findByRole('link', { name: 'Formulierungshilfen Referat 50' })
    await user.click(
      within(cardOf(link)).getByRole('button', {
        name: '„Formulierungshilfen Referat 50“ als Favorit markieren',
      }),
    )
    await within(cardOf(link)).findByRole('button', { name: /aus den Favoriten entfernen/ })

    const chip = screen.getByRole('button', { name: 'Favoriten' })
    expect(chip).toHaveAttribute('aria-pressed', 'false')
    await user.click(chip)

    expect(chip).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() =>
      expect(
        screen.queryByRole('link', { name: /Rechtsquellen Soziales/ }),
      ).not.toBeInTheDocument(),
    )
    expect(screen.getByRole('link', { name: 'Formulierungshilfen Referat 50' })).toBeInTheDocument()
    expect(requestedUrls.at(-1)?.searchParams.get('favorites')).toBe('true')
    expect(screen.getByTestId('location')).toHaveTextContent('/catalog?favorites=1')
  })

  it('starts narrowed to the favorites the address names', async () => {
    renderCatalog('/catalog?favorites=1')

    expect(await screen.findByRole('button', { name: 'Favoriten' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
    await waitFor(() => expect(requestedUrls.at(-1)?.searchParams.get('favorites')).toBe('true'))
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

  it('offers "In Space verwenden" in a menu beside the star, never inside the link', async () => {
    const user = userEvent.setup()
    renderCatalog()
    const link = await screen.findByRole('link', { name: 'Formulierungshilfen Referat 50' })
    const card = cardOf(link)

    expect(
      within(card).queryByRole('button', { name: /in Space verwenden/ }),
    ).not.toBeInTheDocument()
    const more = within(card).getByRole('button', {
      name: 'Weitere Aktionen für ‚Formulierungshilfen Referat 50‘',
    })
    expect(within(link).queryByRole('button')).not.toBeInTheDocument()
    expect(more).toHaveAttribute('aria-haspopup', 'menu')

    more.focus()
    await user.keyboard('{Enter}')
    const item = await screen.findByRole('menuitem', { name: 'In Space verwenden' })
    await user.click(item)

    const dialog = await screen.findByRole('dialog', {
      name: '„Formulierungshilfen Referat 50“ in Space verwenden',
    })
    expect(
      await within(dialog).findByRole('button', { name: 'Neuen Space damit anlegen' }),
    ).toBeInTheDocument()
  })

  it('offers no visibility, no group filter and no sort - search, type, favorites in one row', async () => {
    renderCatalog()
    const search = await screen.findByRole('searchbox', { name: 'Suchen' })
    await screen.findByRole('link', { name: /Rechtsquellen Soziales/ })

    expect(screen.queryByRole('group', { name: 'Sichtbarkeit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('group', { name: 'Sortierung' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Aus meinen Gruppen' })).not.toBeInTheDocument()
    const type = screen.getByRole('group', { name: 'Typ' })
    const favorites = screen.getByRole('button', { name: 'Favoriten' })
    expect(search.compareDocumentPosition(type) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(type.compareDocumentPosition(favorites) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(requestedUrls.at(-1)?.searchParams.get('sort')).toBeNull()
    expect(requestedUrls.at(-1)?.searchParams.get('fromMyGroups')).toBeNull()
  })

  describe('tile fields and filters (#2093, #2129)', () => {
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

    it('marks only an entry released to all accounts, shows no role and the date of a ready entry', async () => {
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

      const knowledge = cardOf(await screen.findByRole('link', { name: /Bauordnung/ }))
      const globe = within(knowledge).getByRole('img', { name: 'Für alle Konten freigegeben' })
      // right beside the type badge, as the sketch shows it
      const badge = within(knowledge).getByText('Wissen')
      expect(badge.compareDocumentPosition(globe) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      expect(within(knowledge).queryByText('Für alle')).not.toBeInTheDocument()
      expect(within(knowledge).queryByText('Verwalter')).not.toBeInTheDocument()
      expect(within(knowledge).getByText('Aktualisiert am 18.08.2026')).toBeInTheDocument()
      const prompts = cardOf(screen.getByRole('link', { name: /Bescheidbausteine/ }))
      expect(
        within(prompts).queryByRole('img', { name: 'Für alle Konten freigegeben' }),
      ).not.toBeInTheDocument()
      expect(within(prompts).queryByText('Eingeschränkt')).not.toBeInTheDocument()
      expect(within(prompts).queryByText('Leser')).not.toBeInTheDocument()
      // A type without a measure of its own is READY; its date is the last change.
      const updated = within(prompts).getByText('Aktualisiert am 30.09.2026')
      // A ready entry carries no coloured dot: the date stands alone, not beside one.
      expect(updated.parentElement).toBe(prompts)
    })

    it('marks a private library with the word „Privat“ beside its type badge (#2164)', async () => {
      const knowledge = {
        sourceType: 'NEXTCLOUD' as const,
        indexingStatus: 'READY' as const,
        lastIndexedAt: '2026-08-18T06:00:00Z',
      }
      serve([
        entry('Meine Ablage', {
          assetType: 'KNOWLEDGE_LIBRARY',
          assetId: 'library-private',
          knowledgeLibrary: knowledge,
        }),
        entry('Bauordnung', {
          assetType: 'KNOWLEDGE_LIBRARY',
          assetId: 'library-shared',
          knowledgeLibrary: knowledge,
        }),
      ])
      server.use(
        http.get('/api/v1/libraries', () =>
          HttpResponse.json([
            {
              id: 'library-private',
              name: 'Meine Ablage',
              ownerType: 'USER',
              reach: { allAccounts: false, groupCount: 0, userCount: 1 },
              myRole: 'OWNER',
              sourceType: 'NEXTCLOUD',
              documentCount: 3,
              privateLibrary: true,
              createdAt: '2026-03-01T10:00:00Z',
              updatedAt: '2026-03-01T10:00:00Z',
            },
            {
              id: 'library-shared',
              name: 'Bauordnung',
              ownerType: 'USER',
              reach: { allAccounts: false, groupCount: 0, userCount: 1 },
              myRole: 'OWNER',
              sourceType: 'NEXTCLOUD',
              documentCount: 3,
              privateLibrary: false,
              createdAt: '2026-03-01T10:00:00Z',
              updatedAt: '2026-03-01T10:00:00Z',
            },
          ]),
        ),
      )
      renderCatalog()

      const own = cardOf(await screen.findByRole('link', { name: /Meine Ablage/ }))
      const mark = await within(own).findByText('Privat')
      const badge = within(own).getByText('Wissen')
      expect(badge.compareDocumentPosition(mark) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      const shared = cardOf(screen.getByRole('link', { name: /Bauordnung/ }))
      expect(within(shared).queryByText('Privat')).not.toBeInTheDocument()
    })

    // An upload library has no runs and so no lastIndexedAt; its date is its last change.
    it('gives a ready upload library its last change as date', async () => {
      serve([
        entry('Hochgeladenes', {
          assetType: 'KNOWLEDGE_LIBRARY',
          updatedAt: '2026-09-12T08:00:00Z',
          knowledgeLibrary: { sourceType: 'UPLOAD', indexingStatus: 'READY' },
        }),
      ])
      renderCatalog()

      const card = cardOf(await screen.findByRole('link', { name: /Hochgeladenes/ }))
      expect(within(card).getByText('Aktualisiert am 12.09.2026')).toBeInTheDocument()
    })

    // #2207: no visible title before the type group; its accessible name stays "Typ".
    it('names the type filter "Typ" without a visible title', async () => {
      renderCatalog()
      await screen.findByRole('link', { name: /Rechtsquellen Soziales/ })

      const group = screen.getByRole('group', { name: 'Typ' })
      expect(group).toHaveAttribute('aria-label', 'Typ')
      expect(screen.queryByText('Typ', { exact: true })).not.toBeInTheDocument()
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

      const failed = cardOf(await screen.findByRole('link', { name: /Fehlgeschlagen/ }))
      const failedState = within(failed).getByText('Aktualisierung fehlgeschlagen')
      expect(failedState.parentElement?.querySelector('[aria-hidden="true"]')).not.toBeNull()
      const orphaned = cardOf(screen.getByRole('link', { name: /Verwaist/ }))
      expect(within(orphaned).getByText('Wird aktualisiert')).toBeInTheDocument()
      expect(within(orphaned).getByText('Nachfolge offen')).toBeInTheDocument()
      const empty = cardOf(screen.getByRole('link', { name: /Leer/ }))
      expect(within(empty).getByText('Noch kein Inhalt')).toBeInTheDocument()
    })

    it('starts with every filter the address names', async () => {
      renderCatalog('/catalog?type=knowledge&favorites=1')

      await waitFor(() => expect(requestedUrls.length).toBeGreaterThan(0))
      const params = requestedUrls.at(-1)!.searchParams
      expect(params.get('type')).toBe('KNOWLEDGE_LIBRARY')
      expect(params.get('favorites')).toBe('true')
    })
  })
})
