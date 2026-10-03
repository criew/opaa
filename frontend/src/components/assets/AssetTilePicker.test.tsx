import { describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { useState } from 'react'
import { renderWithProviders } from '../../test/test-utils'
import { server } from '../../mocks/server'
import type { CatalogEntryResponse } from '../../types/api'
import AssetTilePicker from './AssetTilePicker'
import type { AssetPick } from './assetPick'

function entry(assetId: string, name: string): CatalogEntryResponse {
  return {
    assetType: 'KNOWLEDGE_LIBRARY',
    assetId,
    name,
    description: null,
    ownerType: 'USER',
    ownerLabel: null,
    origin: 'LOCAL',
    itemCount: 0,
    spaceCount: 0,
    succession: null,
  } as unknown as CatalogEntryResponse
}

function Harness() {
  const [value, setValue] = useState<AssetPick[]>([])
  return <AssetTilePicker value={value} onChange={setValue} aria-label="Inhalte" />
}

describe('AssetTilePicker', () => {
  it('keeps the tiles already shown when a further page fails, and offers to try again', async () => {
    let secondPageCalls = 0
    server.use(
      http.get('/api/v1/catalog', ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page') ?? '0')
        if (page === 0) {
          return HttpResponse.json({
            entries: [entry('erste', 'Erste Bibliothek')],
            page: 0,
            size: 50,
            totalElements: 2,
            totalPages: 2,
          })
        }
        secondPageCalls += 1
        if (secondPageCalls === 1) {
          return HttpResponse.json({ error: 'Dienst nicht erreichbar' }, { status: 503 })
        }
        return HttpResponse.json({
          entries: [entry('zweite', 'Zweite Bibliothek')],
          page: 1,
          size: 50,
          totalElements: 2,
          totalPages: 2,
        })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    const more = await screen.findByRole('button', { name: 'Weitere laden' })
    expect(screen.getByText('1 von 2 angezeigt')).toBeVisible()
    await user.click(more)

    expect(await screen.findByText(/Weitere Einträge konnten nicht geladen werden/)).toBeVisible()
    expect(screen.getByRole('checkbox', { name: /^Erste Bibliothek/ })).toBeVisible()

    await user.click(screen.getByRole('button', { name: 'Erneut versuchen' }))

    expect(await screen.findByRole('checkbox', { name: /^Zweite Bibliothek/ })).toBeVisible()
    expect(screen.getByRole('checkbox', { name: /^Erste Bibliothek/ })).toBeVisible()
    expect(
      screen.queryByText(/Weitere Einträge konnten nicht geladen werden/),
    ).not.toBeInTheDocument()
  })

  /** #2131: the star is a control of its own - it marks the favorite and leaves the choice. */
  it('marks a favorite from a tile without changing the choice', async () => {
    const marked: string[] = []
    server.use(
      http.get('/api/v1/catalog', () =>
        HttpResponse.json({
          entries: [{ ...entry('erste', 'Erste Bibliothek'), favorite: false }],
          page: 0,
          size: 50,
          totalElements: 1,
          totalPages: 1,
        }),
      ),
      http.put('/api/v1/assets/:assetType/:assetId/favorite', ({ params }) => {
        marked.push(String(params.assetId))
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    const checkbox = await screen.findByRole('checkbox', { name: 'Erste Bibliothek' })
    await user.click(
      screen.getByRole('button', { name: '„Erste Bibliothek“ als Favorit markieren' }),
    )

    expect(
      await screen.findByRole('button', { name: '„Erste Bibliothek“ aus den Favoriten entfernen' }),
    ).toBeInTheDocument()
    expect(marked).toEqual(['erste'])
    expect(checkbox).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByText('Nichts ausgewählt.')).toBeInTheDocument()

    await user.click(checkbox)
    expect(checkbox).toHaveAttribute('aria-checked', 'true')
    expect(screen.getByText('Ausgewählt: Erste Bibliothek')).toBeInTheDocument()
  })

  /** Review #2145: after a star, the next page by offset would repeat one entry and skip another. */
  it('loads further pages after a star without repeating or skipping an entry', async () => {
    const names = Array.from({ length: 60 }, (_, i) => `Eintrag ${String(i).padStart(2, '0')}`)
    const favorites = new Set<string>(['Eintrag 55'])
    server.use(
      http.get('/api/v1/catalog', ({ request }) => {
        const params = new URL(request.url).searchParams
        const page = Number(params.get('page') ?? '0')
        const size = Number(params.get('size') ?? '50')
        const all = [...names]
          .sort((a, b) => Number(favorites.has(b)) - Number(favorites.has(a)) || a.localeCompare(b))
          .map((name) => ({ ...entry(name, name), favorite: favorites.has(name) }))
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
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    await screen.findByRole('checkbox', { name: 'Eintrag 55' })
    await user.click(
      screen.getByRole('button', { name: '„Eintrag 55“ aus den Favoriten entfernen' }),
    )
    await screen.findByRole('button', { name: '„Eintrag 55“ als Favorit markieren' })
    await user.click(screen.getByRole('button', { name: 'Weitere laden' }))

    await waitFor(() => expect(screen.getAllByRole('checkbox')).toHaveLength(60))
    expect(screen.getAllByRole('checkbox', { name: 'Eintrag 55' })).toHaveLength(1)
    expect(screen.getByRole('checkbox', { name: 'Eintrag 49' })).toBeInTheDocument()
  })

  /** Review #2145: narrowed to the chosen, a tile is the same full tile as in the list. */
  it('shows the chosen as full catalog tiles and leaves out what is not readable', async () => {
    const requested: string[][] = []
    server.use(
      http.get('/api/v1/catalog', ({ request }) => {
        const ids = new URL(request.url).searchParams.getAll('ids')
        requested.push(ids)
        const readable = [
          {
            ...entry('erste', 'Erste Bibliothek'),
            itemCount: 12,
            spaceCount: 2,
            ownerLabel: 'Bürgerbüro',
            ownerType: 'GROUP',
            favorite: true,
          },
        ]
        const entries = readable.filter((e) => ids.length === 0 || ids.includes(e.assetId))
        return HttpResponse.json({
          entries,
          page: 0,
          size: 200,
          totalElements: entries.length,
          totalPages: 1,
        })
      }),
    )
    const chosen: AssetPick[] = [
      { assetType: 'KNOWLEDGE_LIBRARY', assetId: 'erste', name: 'Erste Bibliothek' },
      { assetType: 'KNOWLEDGE_LIBRARY', assetId: 'fremde', name: 'Fremde Bibliothek' },
    ]
    renderWithProviders(
      <AssetTilePicker
        value={chosen}
        onChange={() => undefined}
        chosenOnlyLabel="Nur zugeordnete"
        aria-label="Inhalte"
      />,
    )

    const group = await screen.findByRole('group', { name: 'Inhalte' })
    await waitFor(() =>
      expect(within(group).getByText('12 Dokumente · in 2 Spaces')).toBeInTheDocument(),
    )
    expect(
      within(group).getByRole('button', { name: '„Erste Bibliothek“ aus den Favoriten entfernen' }),
    ).toBeInTheDocument()
    expect(within(group).getByText('Bürgerbüro')).toBeInTheDocument()
    expect(within(group).queryByRole('checkbox', { name: 'Fremde Bibliothek' })).toBeNull()
    expect(requested).toContainEqual(expect.arrayContaining(['erste', 'fremde']))
  })
})
