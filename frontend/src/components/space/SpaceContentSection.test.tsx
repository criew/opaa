import { beforeEach, describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from '../../mocks/server'
import { favoriteKey, mockFavoriteAssets } from '../../mocks/assetFixtures'
import { renderWithProviders } from '../../test/test-utils'
import { ASSOCIATIONS_NOT_REFRESHED, useSpaceStore } from '../../stores/spaceStore'
import { resetAllStores } from '../../stores/resettableStores'
import type { AssetType, SpaceAssetAssociationResponse } from '../../types/api'
import SpaceContentSection from './SpaceContentSection'

const SPACE_ID = 'space-content'

const NAMES: Record<string, string> = {
  'library-referat-50': 'Rechtsquellen Soziales',
  'library-dienstanweisungen': 'Dienstanweisungen',
  'library-solo-owner': 'Projektakte Phoenix',
  'prompt-library-referat-50': 'Formulierungshilfen Referat 50',
}

function association(assetType: AssetType, assetId: string): SpaceAssetAssociationResponse {
  return {
    assetType,
    assetId,
    name: NAMES[assetId] ?? assetId,
    description: null,
    createdByUserId: 'mock-user-id',
    createdAt: '2026-10-01T10:00:00Z',
  }
}

interface ServeOptions {
  hasUnreadable?: boolean
  /** Answers every POST with this status instead of associating. */
  failPostWith?: number
  /** Answers the POST for these asset ids with 500 instead of associating. */
  failPostFor?: string[]
  /** Answers every DELETE with this status instead of detaching. */
  failDeleteWith?: number
  /** Answers every list request after the first this many with 500. */
  failGetAfter?: number
  /** Holds every DELETE until this promise settles. */
  holdDelete?: Promise<void>
  /** The associations of further spaces, by space id. */
  others?: Record<string, SpaceAssetAssociationResponse[]>
}

/** The association endpoints over one mutable list per space, recording every write. */
function serveAssociations(initial: SpaceAssetAssociationResponse[], options: ServeOptions = {}) {
  const lists = new Map<string, SpaceAssetAssociationResponse[]>([
    [SPACE_ID, [...initial]],
    ...Object.entries(options.others ?? {}),
  ])
  const listOf = (spaceId: unknown) => lists.get(String(spaceId)) ?? []
  const posts: Array<{ assetType: string; assetId: string }> = []
  const postedTo: string[] = []
  const deletes: string[] = []
  let gets = 0
  server.use(
    http.get('/api/v1/spaces/:spaceId/assets', ({ params }) => {
      gets += 1
      if (options.failGetAfter !== undefined && gets > options.failGetAfter) {
        return HttpResponse.json({ error: 'Dienst nicht erreichbar' }, { status: 500 })
      }
      const items = listOf(params.spaceId)
      return HttpResponse.json({
        hasAssociations: items.length > 0 || Boolean(options.hasUnreadable),
        hasUnreadableAssociations: Boolean(options.hasUnreadable),
        hasKnowledge: items.some((item) => item.assetType === 'KNOWLEDGE_LIBRARY'),
        hasReadableKnowledge: items.some((item) => item.assetType === 'KNOWLEDGE_LIBRARY'),
        items,
      })
    }),
    http.post('/api/v1/spaces/:spaceId/assets', async ({ params, request }) => {
      const body = (await request.json()) as { assetType: AssetType; assetId: string }
      posts.push(body)
      postedTo.push(String(params.spaceId))
      if (options.failPostWith || options.failPostFor?.includes(body.assetId)) {
        return HttpResponse.json(
          { error: 'Zuordnung derzeit nicht möglich' },
          { status: options.failPostWith ?? 500 },
        )
      }
      const created = association(body.assetType, body.assetId)
      const items = listOf(params.spaceId)
      lists.set(String(params.spaceId), [
        ...items.filter((item) => item.assetId !== body.assetId),
        created,
      ])
      return HttpResponse.json(created, { status: 201 })
    }),
    http.delete('/api/v1/spaces/:spaceId/assets/:assetId', async ({ params }) => {
      deletes.push(String(params.assetId))
      if (options.holdDelete) await options.holdDelete
      if (options.failDeleteWith) {
        return HttpResponse.json(
          { error: 'Lösen derzeit nicht möglich' },
          { status: options.failDeleteWith },
        )
      }
      lists.set(
        String(params.spaceId),
        listOf(params.spaceId).filter((item) => item.assetId !== String(params.assetId)),
      )
      return new HttpResponse(null, { status: 204 })
    }),
  )
  return { posts, postedTo, deletes }
}

function renderSection(canManage = true) {
  return renderWithProviders(<SpaceContentSection spaceId={SPACE_ID} canManage={canManage} />)
}

function tile(name: string) {
  return screen.findByRole('checkbox', { name: new RegExp(`^${name}`) })
}

function onlyAssociatedChip() {
  return screen.getByRole('button', { name: 'Nur zugeordnete' })
}

describe('SpaceContentSection', () => {
  // The space store outlives a test; a previous test's associations would show up in the next.
  beforeEach(() => resetAllStores())

  // #2207: the tab already reads "Inhalte" - no heading repeats it, and no lead sentence.
  it('opens on the associated contents, with "Nur zugeordnete" switched on and no heading', async () => {
    serveAssociations([association('KNOWLEDGE_LIBRARY', 'library-referat-50')])
    renderSection()

    expect(screen.queryByRole('heading', { name: 'Inhalte' })).not.toBeInTheDocument()
    expect(screen.queryByText(/nutzt nur, was hier ausgewählt ist/)).not.toBeInTheDocument()
    expect(await tile('Rechtsquellen Soziales')).toHaveAttribute('aria-checked', 'true')
    expect(onlyAssociatedChip()).toHaveAttribute('aria-pressed', 'true')
    expect(screen.queryByRole('checkbox', { name: /^Dienstanweisungen/ })).not.toBeInTheDocument()
  })

  it('shows everything readable once "Nur zugeordnete" is switched off, the associated checked', async () => {
    serveAssociations([association('KNOWLEDGE_LIBRARY', 'library-referat-50')])
    renderSection()
    const user = userEvent.setup()
    await tile('Rechtsquellen Soziales')

    await user.click(onlyAssociatedChip())

    expect(onlyAssociatedChip()).toHaveAttribute('aria-pressed', 'false')
    expect(await tile('Dienstanweisungen')).toHaveAttribute('aria-checked', 'false')
    expect(await tile('Formulierungshilfen Referat 50')).toHaveAttribute('aria-checked', 'false')
    expect(await tile('Rechtsquellen Soziales')).toHaveAttribute('aria-checked', 'true')
  })

  it('offers the filter row of the catalog: search, then type, then favorites', async () => {
    serveAssociations([])
    renderSection()

    const search = screen.getByRole('searchbox', { name: 'Suchen' })
    const type = screen.getByRole('group', { name: 'Typ' })
    const filters = screen.getByRole('group', { name: 'Filter' })
    const favorites = within(filters).getByRole('button', { name: 'Favoriten' })
    expect(search.compareDocumentPosition(type) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(type.compareDocumentPosition(favorites) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(
      favorites.compareDocumentPosition(onlyAssociatedChip()) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
  })

  it('narrows the associated contents by type, search and favorites', async () => {
    mockFavoriteAssets.add(favoriteKey('KNOWLEDGE_LIBRARY', 'library-dienstanweisungen'))
    serveAssociations([
      association('KNOWLEDGE_LIBRARY', 'library-referat-50'),
      association('KNOWLEDGE_LIBRARY', 'library-dienstanweisungen'),
      association('PROMPT_LIBRARY', 'prompt-library-referat-50'),
    ])
    renderSection()
    const user = userEvent.setup()
    await tile('Rechtsquellen Soziales')

    await user.click(within(screen.getByRole('group', { name: 'Typ' })).getByText('Prompts'))
    expect(await tile('Formulierungshilfen Referat 50')).toBeVisible()
    expect(
      screen.queryByRole('checkbox', { name: /^Rechtsquellen Soziales/ }),
    ).not.toBeInTheDocument()

    await user.click(within(screen.getByRole('group', { name: 'Typ' })).getByText('Alle'))
    await user.type(screen.getByRole('searchbox', { name: 'Suchen' }), 'rechtsquellen')
    await waitFor(() =>
      expect(
        screen.queryByRole('checkbox', { name: /^Dienstanweisungen/ }),
      ).not.toBeInTheDocument(),
    )
    expect(await tile('Rechtsquellen Soziales')).toBeVisible()

    await user.clear(screen.getByRole('searchbox', { name: 'Suchen' }))
    await user.click(
      within(screen.getByRole('group', { name: 'Filter' })).getByRole('button', {
        name: 'Favoriten',
      }),
    )
    expect(await tile('Dienstanweisungen')).toBeVisible()
    await waitFor(() =>
      expect(
        screen.queryByRole('checkbox', { name: /^Rechtsquellen Soziales/ }),
      ).not.toBeInTheDocument(),
    )
  })

  it('associates on the check mark at once and confirms it in a live notification', async () => {
    const { posts } = serveAssociations([])
    renderSection()
    const user = userEvent.setup()
    await screen.findByText(/noch nichts zugeordnet/)
    await user.click(onlyAssociatedChip())

    await user.click(await tile('Dienstanweisungen'))

    expect(await screen.findByRole('alert')).toHaveTextContent(/Dienstanweisungen.*zugeordnet/)
    expect(posts).toEqual([
      { assetType: 'KNOWLEDGE_LIBRARY', assetId: 'library-dienstanweisungen' },
    ])
    expect(await tile('Dienstanweisungen')).toHaveAttribute('aria-checked', 'true')
    expect(screen.queryByRole('button', { name: 'Zuordnen' })).not.toBeInTheDocument()
  })

  it('detaches on removing the check mark, keeps the tile in place and offers to undo it', async () => {
    const { posts, deletes } = serveAssociations([
      association('KNOWLEDGE_LIBRARY', 'library-referat-50'),
    ])
    renderSection()
    const user = userEvent.setup()

    await user.click(await tile('Rechtsquellen Soziales'))

    const notice = await screen.findByRole('alert')
    expect(notice).toHaveTextContent(/Rechtsquellen Soziales.*gelöst/)
    expect(deletes).toEqual(['library-referat-50'])
    // The view stays on "Nur zugeordnete": the tile just detached stays where the focus is.
    const detached = await tile('Rechtsquellen Soziales')
    expect(detached).toHaveAttribute('aria-checked', 'false')
    expect(detached).toHaveFocus()

    await user.click(within(notice).getByRole('button', { name: 'Rückgängig' }))

    await waitFor(() =>
      expect(posts).toEqual([{ assetType: 'KNOWLEDGE_LIBRARY', assetId: 'library-referat-50' }]),
    )
    await waitFor(async () =>
      expect(await tile('Rechtsquellen Soziales')).toHaveAttribute('aria-checked', 'true'),
    )
  })

  it('springs the check mark back and names the error when associating fails', async () => {
    serveAssociations([], { failPostWith: 500 })
    renderSection()
    const user = userEvent.setup()
    await screen.findByText(/noch nichts zugeordnet/)
    await user.click(onlyAssociatedChip())

    await user.click(await tile('Dienstanweisungen'))

    expect(await screen.findByRole('alert')).toHaveTextContent('Zuordnung derzeit nicht möglich')
    await waitFor(async () =>
      expect(await tile('Dienstanweisungen')).toHaveAttribute('aria-checked', 'false'),
    )
  })

  it('springs the check mark back and names the error when detaching fails', async () => {
    serveAssociations([association('KNOWLEDGE_LIBRARY', 'library-referat-50')], {
      failDeleteWith: 500,
    })
    renderSection()
    const user = userEvent.setup()

    await user.click(await tile('Rechtsquellen Soziales'))

    expect(await screen.findByRole('alert')).toHaveTextContent('Lösen derzeit nicht möglich')
    await waitFor(async () =>
      expect(await tile('Rechtsquellen Soziales')).toHaveAttribute('aria-checked', 'true'),
    )
  })

  it('catches a double click on a tile without disabling it, so the focus stays', async () => {
    const { posts, deletes } = serveAssociations([])
    renderSection()
    const user = userEvent.setup()
    await screen.findByText(/noch nichts zugeordnet/)
    await user.click(onlyAssociatedChip())
    const target = await tile('Dienstanweisungen')

    await user.dblClick(target)

    expect(await screen.findByRole('alert')).toHaveTextContent(/zugeordnet/)
    expect(posts).toHaveLength(1)
    expect(deletes).toHaveLength(0)
    const after = await tile('Dienstanweisungen')
    expect(after).not.toBeDisabled()
    expect(after).toHaveFocus()
    expect(after).toHaveAttribute('aria-checked', 'true')
  })

  it('associates and detaches by keyboard, the focus staying on the tile', async () => {
    const { posts, deletes } = serveAssociations([])
    renderSection()
    const user = userEvent.setup()
    await screen.findByText(/noch nichts zugeordnet/)
    await user.click(onlyAssociatedChip())
    ;(await tile('Dienstanweisungen')).focus()

    await user.keyboard(' ')
    await waitFor(async () =>
      expect(await tile('Dienstanweisungen')).toHaveAttribute('aria-checked', 'true'),
    )
    await waitFor(() => expect(posts).toHaveLength(1))
    expect(await tile('Dienstanweisungen')).toHaveFocus()

    await user.keyboard(' ')
    await waitFor(() => expect(deletes).toEqual(['library-dienstanweisungen']))
    expect(await tile('Dienstanweisungen')).toHaveAttribute('aria-checked', 'false')
    expect(await tile('Dienstanweisungen')).toHaveFocus()
  })

  it('shows the associated contents read-only to someone who may not curate', async () => {
    const { posts, deletes } = serveAssociations([
      association('KNOWLEDGE_LIBRARY', 'library-referat-50'),
    ])
    renderSection(false)
    const user = userEvent.setup()

    const associated = await tile('Rechtsquellen Soziales')
    expect(associated).toHaveAttribute('aria-checked', 'true')
    expect(associated).toHaveAttribute('aria-readonly', 'true')
    expect(screen.queryByRole('button', { name: 'Nur zugeordnete' })).not.toBeInTheDocument()

    await user.click(associated)

    expect(associated).toHaveAttribute('aria-checked', 'true')
    expect(posts).toHaveLength(0)
    expect(deletes).toHaveLength(0)
  })

  // ADR-0039, Entscheidung 2: an association the caller cannot read leaves no name and no number.
  it('names unreadable associations only by the count-free hint', async () => {
    serveAssociations([association('KNOWLEDGE_LIBRARY', 'library-referat-50')], {
      hasUnreadable: true,
    })
    renderSection()

    const hint = await screen.findByText('Nicht alle zugeordneten Inhalte sind für Sie lesbar.')
    expect(hint.textContent).not.toMatch(/\d/)
    expect(await tile('Rechtsquellen Soziales')).toBeVisible()
  })

  it('shows no hint when every association is readable', async () => {
    serveAssociations([association('KNOWLEDGE_LIBRARY', 'library-referat-50')])
    renderSection()

    await tile('Rechtsquellen Soziales')
    expect(
      screen.queryByText('Nicht alle zugeordneten Inhalte sind für Sie lesbar.'),
    ).not.toBeInTheDocument()
  })

  describe('Space-Wechsel', () => {
    const OTHER_ID = 'space-other'

    // Back and forward in the history keep the page and swap only the space id.
    it('shows only the associations of its own space, not those still held for another', async () => {
      serveAssociations([association('KNOWLEDGE_LIBRARY', 'library-dienstanweisungen')], {
        others: { [OTHER_ID]: [association('KNOWLEDGE_LIBRARY', 'library-referat-50')] },
      })
      await useSpaceStore.getState().loadAssetAssociations(OTHER_ID)
      expect(useSpaceStore.getState().assetAssociationsSpaceId).toBe(OTHER_ID)

      renderSection()

      expect(await tile('Dienstanweisungen')).toHaveAttribute('aria-checked', 'true')
      expect(
        screen.queryByRole('checkbox', { name: /^Rechtsquellen Soziales/ }),
      ).not.toBeInTheDocument()
    })

    it('starts afresh when the same tab switches from one space to another', async () => {
      serveAssociations([association('KNOWLEDGE_LIBRARY', 'library-dienstanweisungen')], {
        others: { [OTHER_ID]: [association('KNOWLEDGE_LIBRARY', 'library-referat-50')] },
      })
      const { rerender } = renderSection()
      await tile('Dienstanweisungen')

      rerender(<SpaceContentSection spaceId={OTHER_ID} canManage />)

      expect(await tile('Rechtsquellen Soziales')).toHaveAttribute('aria-checked', 'true')
      expect(screen.queryByRole('checkbox', { name: /^Dienstanweisungen/ })).not.toBeInTheDocument()
    })

    it('withdraws the undo of the space left behind', async () => {
      serveAssociations([association('KNOWLEDGE_LIBRARY', 'library-referat-50')], {
        others: { [OTHER_ID]: [association('KNOWLEDGE_LIBRARY', 'library-dienstanweisungen')] },
      })
      const user = userEvent.setup()
      const { rerender } = renderSection()
      await user.click(await tile('Rechtsquellen Soziales'))
      expect(await screen.findByRole('button', { name: 'Rückgängig' })).toBeVisible()

      rerender(<SpaceContentSection spaceId={OTHER_ID} canManage />)

      await waitFor(() =>
        expect(screen.queryByRole('button', { name: 'Rückgängig' })).not.toBeInTheDocument(),
      )
      expect(await tile('Dienstanweisungen')).toHaveAttribute('aria-checked', 'true')
      expect(useSpaceStore.getState().assetAssociationsSpaceId).toBe(OTHER_ID)
    })

    it('lets a change still running in the space left behind leave the new one untouched', async () => {
      let release: () => void = () => {}
      const { deletes } = serveAssociations(
        [association('KNOWLEDGE_LIBRARY', 'library-referat-50')],
        {
          others: { [OTHER_ID]: [association('KNOWLEDGE_LIBRARY', 'library-dienstanweisungen')] },
          holdDelete: new Promise<void>((resolve) => (release = resolve)),
        },
      )
      const user = userEvent.setup()
      const { rerender } = renderSection()
      await user.click(await tile('Rechtsquellen Soziales'))
      await waitFor(() => expect(deletes).toEqual(['library-referat-50']))

      rerender(<SpaceContentSection spaceId={OTHER_ID} canManage />)
      await tile('Dienstanweisungen')
      release()

      await waitFor(() =>
        expect(useSpaceStore.getState().assetAssociations.map((a) => a.assetId)).toEqual([
          'library-dienstanweisungen',
        ]),
      )
      expect(useSpaceStore.getState().assetAssociationsSpaceId).toBe(OTHER_ID)
      expect(await tile('Dienstanweisungen')).toHaveAttribute('aria-checked', 'true')
    })
  })

  it('keeps a focused tile in place while a new search loads', async () => {
    serveAssociations([])
    renderSection()
    const user = userEvent.setup()
    await screen.findByText(/noch nichts zugeordnet/)
    await user.click(onlyAssociatedChip())
    await tile('Projektakte Phoenix')

    await user.type(screen.getByRole('searchbox', { name: 'Suchen' }), 'Dienst')
    const target = await tile('Dienstanweisungen')
    target.focus()

    await waitFor(() =>
      expect(
        screen.queryByRole('checkbox', { name: /^Projektakte Phoenix/ }),
      ).not.toBeInTheDocument(),
    )
    expect(await tile('Dienstanweisungen')).toHaveFocus()
  })

  it('returns the focus to the tile once its detaching is undone', async () => {
    serveAssociations([association('KNOWLEDGE_LIBRARY', 'library-referat-50')])
    renderSection()
    const user = userEvent.setup()

    await user.click(await tile('Rechtsquellen Soziales'))
    await user.click(await screen.findByRole('button', { name: 'Rückgängig' }))

    const restored = await tile('Rechtsquellen Soziales')
    expect(restored).toHaveFocus()
    await waitFor(() => expect(restored).toHaveAttribute('aria-checked', 'true'))
  })

  it('names the error and leaves the check mark off when the undo fails', async () => {
    const { deletes, posts } = serveAssociations(
      [association('KNOWLEDGE_LIBRARY', 'library-referat-50')],
      { failPostWith: 500 },
    )
    renderSection()
    const user = userEvent.setup()

    await user.click(await tile('Rechtsquellen Soziales'))
    await user.click(await screen.findByRole('button', { name: 'Rückgängig' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Zuordnung derzeit nicht möglich')
    expect(deletes).toEqual(['library-referat-50'])
    expect(posts).toHaveLength(1)
    expect(await tile('Rechtsquellen Soziales')).toHaveAttribute('aria-checked', 'false')
  })

  it('associates two tiles clicked in quick succession', async () => {
    const { posts } = serveAssociations([])
    renderSection()
    const user = userEvent.setup()
    await screen.findByText(/noch nichts zugeordnet/)
    await user.click(onlyAssociatedChip())
    const first = await tile('Dienstanweisungen')
    const second = await tile('Projektakte Phoenix')

    await user.click(first)
    await user.click(second)

    await waitFor(() =>
      expect(posts.map((post) => post.assetId).sort()).toEqual([
        'library-dienstanweisungen',
        'library-solo-owner',
      ]),
    )
    await waitFor(async () => {
      expect(await tile('Dienstanweisungen')).toHaveAttribute('aria-checked', 'true')
      expect(await tile('Projektakte Phoenix')).toHaveAttribute('aria-checked', 'true')
    })
  })

  it('keeps an error in view when another tile succeeds right after', async () => {
    serveAssociations([], { failPostFor: ['library-dienstanweisungen'] })
    renderSection()
    const user = userEvent.setup()
    await screen.findByText(/noch nichts zugeordnet/)
    await user.click(onlyAssociatedChip())

    await user.click(await tile('Dienstanweisungen'))
    expect(await screen.findByRole('alert')).toHaveTextContent('Zuordnung derzeit nicht möglich')
    await user.click(await tile('Projektakte Phoenix'))
    await waitFor(async () =>
      expect(await tile('Projektakte Phoenix')).toHaveAttribute('aria-checked', 'true'),
    )

    expect(screen.getByRole('alert')).toHaveTextContent('Zuordnung derzeit nicht möglich')
  })

  it('writes nothing when the space key is pressed on a read-only tile', async () => {
    const { posts, deletes } = serveAssociations([
      association('KNOWLEDGE_LIBRARY', 'library-referat-50'),
    ])
    renderSection(false)
    const user = userEvent.setup()
    const associated = await tile('Rechtsquellen Soziales')
    associated.focus()

    await user.keyboard(' ')
    await user.keyboard('{Enter}')

    expect(associated).toHaveAttribute('aria-checked', 'true')
    expect(posts).toHaveLength(0)
    expect(deletes).toHaveLength(0)
  })

  it('keeps the list and says so when the refresh after a change fails', async () => {
    serveAssociations(
      [
        association('KNOWLEDGE_LIBRARY', 'library-referat-50'),
        association('KNOWLEDGE_LIBRARY', 'library-dienstanweisungen'),
      ],
      { failGetAfter: 1 },
    )
    renderSection()
    const user = userEvent.setup()

    await user.click(await tile('Rechtsquellen Soziales'))

    expect(await screen.findByText(ASSOCIATIONS_NOT_REFRESHED)).toBeVisible()
    expect(await tile('Dienstanweisungen')).toHaveAttribute('aria-checked', 'true')
    expect(await tile('Rechtsquellen Soziales')).toBeVisible()
  })
})
