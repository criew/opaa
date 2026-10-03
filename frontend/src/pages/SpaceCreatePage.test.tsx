import { describe, expect, it, beforeEach, vi } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { answerConfirm, renderWithProviders } from '../test/test-utils'
import { server } from '../mocks/server'
import { favoriteKey, mockFavoriteAssets } from '../mocks/assetFixtures'
import { capabilityMissingMessage } from '../utils/labels'
import SpaceCreatePage, { NO_KNOWLEDGE_SUMMARY } from './SpaceCreatePage'
import { useSpaceStore } from '../stores/spaceStore'

const mockNavigate = vi.fn()
let mockLocationState: unknown = null

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return {
    ...actual,
    useNavigate: () => mockNavigate,
    useLocation: () => ({ ...actual.useLocation(), state: mockLocationState }),
  }
})

const mockCreateNewSpace = vi.fn(async () => 'space-neu')

describe('SpaceCreatePage (#594, Mockup 1b)', () => {
  beforeEach(() => {
    mockLocationState = null
    mockNavigate.mockReset()
    mockCreateNewSpace.mockClear()
    mockCreateNewSpace.mockResolvedValue('space-neu')
    useSpaceStore.setState({ createNewSpace: mockCreateNewSpace })
  })

  it('renders the stepper and blocks "Weiter" until a name is entered', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SpaceCreatePage />, { withRouter: true })

    expect(screen.getByRole('heading', { level: 1, name: 'Neuer Space' })).toBeInTheDocument()
    expect(screen.getByText('1 · Grunddaten')).toBeInTheDocument()
    expect(screen.getByText('2 · Mitglieder')).toBeInTheDocument()
    expect(screen.getByText('3 · Inhalte')).toBeInTheDocument()
    expect(screen.getByText('4 · Zusammenfassung')).toBeInTheDocument()

    expect(screen.getByRole('button', { name: 'Weiter' })).toBeDisabled()
    await user.type(screen.getByLabelText(/Name/), 'Widerspruchsstelle')
    expect(screen.getByRole('button', { name: 'Weiter' })).toBeEnabled()
  })

  it('explains a missing Anlegerecht instead of hiding the button (#1813)', async () => {
    server.use(
      http.get('/api/v1/me/capabilities', () =>
        HttpResponse.json({ capabilities: ['CREATE_LIBRARY'] }),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<SpaceCreatePage />, { withRouter: true })

    const hint = await screen.findByText(capabilityMissingMessage('CREATE_SPACE'))
    await user.type(screen.getByLabelText(/Name/), 'Widerspruchsstelle')
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    const createButton = screen.getByRole('button', { name: 'Space anlegen' })
    expect(createButton).toBeDisabled()
    // the reason is not merely on the page, it is attached to the button that cannot be used
    expect(createButton).toHaveAccessibleDescription(hint.textContent ?? '')
  })

  it('leaves the button usable while the capabilities are still unknown (#1813)', async () => {
    server.use(http.get('/api/v1/me/capabilities', () => HttpResponse.error()))
    const user = userEvent.setup()
    renderWithProviders(<SpaceCreatePage />, { withRouter: true })

    await user.type(screen.getByLabelText(/Name/), 'Widerspruchsstelle')
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    expect(screen.queryByText(/Ihnen fehlt das Anlegerecht/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Space anlegen' })).toBeEnabled()
  })

  it('keeps entered values when going back a step', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SpaceCreatePage />, { withRouter: true })

    await user.type(screen.getByLabelText(/Name/), 'Widerspruchsstelle')
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    expect(screen.getByText(/Mitglieder lassen sich auch später/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Zurück' }))
    expect(screen.getByLabelText(/Name/)).toHaveValue('Widerspruchsstelle')
  })

  it('shows the summary and creates the space with its members', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SpaceCreatePage />, { withRouter: true })

    await user.type(screen.getByLabelText(/Name/), 'Widerspruchsstelle')
    await user.type(screen.getByLabelText(/Beschreibung/), 'Referat 12')
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))

    expect(screen.getByText('Widerspruchsstelle')).toBeInTheDocument()
    expect(screen.getByText('Referat 12')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Space anlegen' }))

    expect(mockCreateNewSpace).toHaveBeenCalledWith(
      'Widerspruchsstelle',
      'Referat 12',
      [],
      false,
      [],
    )
    expect(mockNavigate).toHaveBeenCalledWith('/spaces/space-neu')
  })

  it('#1923: offers the chat cleanup switch, off by default, and passes it on', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SpaceCreatePage />, { withRouter: true })

    const cleanup = await screen.findByRole('switch', {
      name: 'Inaktive Chats nach 90 Tagen archivieren und nach weiteren 365 Tagen löschen',
    })
    expect(cleanup).not.toBeChecked()
    await user.type(screen.getByLabelText(/Name/), 'Widerspruchsstelle')
    await user.click(cleanup)
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))

    expect(
      screen.getByText(
        'Inaktive Chats werden nach 90 Tagen archiviert und nach weiteren 365 Tagen gelöscht.',
      ),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Space anlegen' }))

    expect(mockCreateNewSpace).toHaveBeenCalledWith('Widerspruchsstelle', '', [], true, [])
  })

  it('#777: offers the user picker on the Mitglieder step, powered by GET /v1/users', async () => {
    // #778 review, finding 2: the shared /api/v1/admin/users MSW handler answers every request
    // unconditionally (it has to - handlers.ts must not import the auth store itself, see
    // src/test/setup.ts's own comment on why pulling the shared axios client in that early breaks
    // request interception for a dozen unrelated tests), so a plain assertion against the picker
    // options would stay green even if the picker regressed to calling GET /v1/admin/users
    // instead of GET /v1/users. This override makes that admin-only endpoint fail exactly as the
    // real backend's SYSTEM_ADMIN-gated AdminController#listUsers would for this caller, so the
    // guard is meaningful: reverting getUserSummaries() back to getUsers() empties the picker and
    // fails this test, instead of passing vacuously against an MSW mock that never enforced the
    // boundary #777 exists to work around.
    server.use(
      http.get('/api/v1/admin/users', () => {
        return HttpResponse.json({ error: 'Zugriff verweigert' }, { status: 403 })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<SpaceCreatePage />, { withRouter: true })

    await user.type(screen.getByLabelText(/Name/), 'Widerspruchsstelle')
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    // #778 review, finding 4: the picker no longer preloads the whole organization - a query
    // (min. 2 characters) has to be typed before GET /v1/users is even attempted.
    await user.type(screen.getByLabelText('Person oder Gruppe suchen'), 'al')
    expect(await screen.findByRole('option', { name: /Alice/ })).toBeInTheDocument()
  })

  /** #2131: Ohne Space-Verzeichnis bewirkt eine Sichtbarkeit nichts - jeder Space ist privat. */
  it('asks for no visibility, neither in the basics nor in the summary', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SpaceCreatePage />, { withRouter: true })

    expect(screen.queryByText(/Sichtbarkeit/)).not.toBeInTheDocument()
    await user.type(screen.getByLabelText(/Name/), 'Widerspruchsstelle')
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))

    expect(screen.queryByText(/Sichtbarkeit/)).not.toBeInTheDocument()
  })

  /** #2131: Die Fristen stammen aus der Systemeinstellung, nicht aus dem Frontend. */
  it('names the cleanup periods the operator configured', async () => {
    server.use(
      http.get('/api/v1/spaces/chat-auto-cleanup', () =>
        HttpResponse.json({ archiveAfterDays: 120, deleteAfterDays: 400 }),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<SpaceCreatePage />, { withRouter: true })

    const cleanup = await screen.findByRole('switch', {
      name: 'Inaktive Chats nach 120 Tagen archivieren und nach weiteren 400 Tagen löschen',
    })
    expect(screen.getByText('Angeheftete Chats sind ausgenommen.')).toBeInTheDocument()
    await user.type(screen.getByLabelText(/Name/), 'Widerspruchsstelle')
    await user.click(cleanup)
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))

    expect(
      screen.getByText(
        'Inaktive Chats werden nach 120 Tagen archiviert und nach weiteren 400 Tagen gelöscht.',
      ),
    ).toBeInTheDocument()
  })

  it('leaves the cleanup out of the summary while it is off', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SpaceCreatePage />, { withRouter: true })

    await screen.findByRole('switch', { name: /nach 90 Tagen archivieren/ })
    await user.type(screen.getByLabelText(/Name/), 'Widerspruchsstelle')
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))

    expect(screen.queryByText('Chats')).not.toBeInTheDocument()
    expect(screen.queryByText(/Inaktive Chats werden/)).not.toBeInTheDocument()
  })

  /** #2131: Personen und Gruppen werden im Assistenten gleich aufgenommen. */
  it('admits a group as a member, names it as a group and passes it on', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SpaceCreatePage />, { withRouter: true })

    await user.type(screen.getByLabelText(/Name/), 'Widerspruchsstelle')
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    expect(
      screen.getByText(/Gruppen geben ihre Rolle an alle ihre Mitglieder weiter/),
    ).toBeInTheDocument()
    await user.type(screen.getByLabelText('Person oder Gruppe suchen'), 'Projektteam')
    await user.click(await screen.findByRole('option', { name: /Referat 5 Projektteam · Gruppe/ }))
    await user.click(screen.getByRole('combobox', { name: 'Rolle des neuen Mitglieds' }))
    await user.click(await screen.findByRole('option', { name: 'Kurator' }))
    await user.click(screen.getByRole('button', { name: 'Vormerken' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))

    expect(screen.getByText(/Referat 5 Projektteam · Gruppe · Kurator/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Space anlegen' }))
    expect(mockCreateNewSpace).toHaveBeenCalledWith('Widerspruchsstelle', '', [], false, [
      { subjectType: 'GROUP', subjectId: 'group-phoenix', role: 'CURATOR' },
    ])
  }, 15000)

  async function noteAlice(user: ReturnType<typeof userEvent.setup>) {
    await user.type(screen.getByLabelText(/Name/), 'Widerspruchsstelle')
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.type(screen.getByLabelText('Person oder Gruppe suchen'), 'al')
    await user.click(await screen.findByRole('option', { name: /Alice/ }))
    await user.click(screen.getByRole('button', { name: 'Vormerken' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
  }

  it('creates the space with its noted members in the same call', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SpaceCreatePage />, { withRouter: true })

    await noteAlice(user)
    await user.click(screen.getByRole('button', { name: 'Space anlegen' }))

    expect(mockCreateNewSpace).toHaveBeenCalledTimes(1)
    expect(mockCreateNewSpace).toHaveBeenCalledWith('Widerspruchsstelle', '', [], false, [
      { subjectType: 'USER', subjectId: expect.any(String), role: 'MEMBER' },
    ])
    expect(mockNavigate).toHaveBeenCalledWith('/spaces/space-neu')
  }, 15000)

  // Space, members and assets are created together or not at all: a refusal leaves no space
  // behind, so the wizard stays open with its entries and says why.
  it('stays in the wizard with an understandable message when the creation is refused', async () => {
    mockCreateNewSpace.mockRejectedValueOnce(new Error('Benutzer nicht gefunden'))
    const user = userEvent.setup()
    renderWithProviders(<SpaceCreatePage />, { withRouter: true })

    await noteAlice(user)
    await user.click(screen.getByRole('button', { name: 'Space anlegen' }))

    expect(
      await screen.findByText(/Der Space wurde nicht angelegt: Benutzer nicht gefunden/),
    ).toBeInTheDocument()
    expect(mockNavigate).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Space anlegen' })).toBeEnabled()
  }, 15000)

  it('asks before cancelling once something was entered', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SpaceCreatePage />, { withRouter: true })

    await user.type(screen.getByLabelText(/Name/), 'W')
    await user.click(screen.getByRole('button', { name: 'Abbrechen' }))
    await answerConfirm(user, 'Eingaben verwerfen und den Assistenten verlassen?', 'Abbrechen')

    expect(mockNavigate).not.toHaveBeenCalled()
  })

  describe('Schritt „Inhalte“', () => {
    async function toContentStep(user: ReturnType<typeof userEvent.setup>) {
      await user.type(screen.getByLabelText(/Name/), 'Widerspruchsstelle')
      await user.click(screen.getByRole('button', { name: 'Weiter' }))
      await user.click(screen.getByRole('button', { name: 'Weiter' }))
    }

    it('can be skipped, and the summary says explicitly that no knowledge is assigned', async () => {
      const user = userEvent.setup()
      renderWithProviders(<SpaceCreatePage />, { withRouter: true })

      await toContentStep(user)
      await user.click(screen.getByRole('button', { name: 'Weiter' }))

      expect(screen.getByText(NO_KNOWLEDGE_SUMMARY)).toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: 'Space anlegen' }))
      expect(mockCreateNewSpace).toHaveBeenCalledWith('Widerspruchsstelle', '', [], false, [])
    })

    it('creates the space with knowledge and prompts chosen as tiles, in one call', async () => {
      const user = userEvent.setup()
      renderWithProviders(<SpaceCreatePage />, { withRouter: true })

      await toContentStep(user)
      await user.click(await screen.findByRole('checkbox', { name: /^Dienstanweisungen/ }))
      await user.click(screen.getByRole('checkbox', { name: /^Formulierungshilfen Referat 50/ }))
      expect(
        screen.getByText('Ausgewählt: Dienstanweisungen, Formulierungshilfen Referat 50'),
      ).toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: 'Weiter' }))

      expect(screen.queryByText(NO_KNOWLEDGE_SUMMARY)).not.toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: 'Space anlegen' }))
      expect(mockCreateNewSpace).toHaveBeenCalledWith(
        'Widerspruchsstelle',
        '',
        [
          { assetType: 'KNOWLEDGE_LIBRARY', assetId: 'library-dienstanweisungen' },
          { assetType: 'PROMPT_LIBRARY', assetId: 'prompt-library-referat-50' },
        ],
        false,
        [],
      )
    })

    it('keeps a choice when the type filter or the search hides its tile', async () => {
      const user = userEvent.setup()
      renderWithProviders(<SpaceCreatePage />, { withRouter: true })

      await toContentStep(user)
      await user.click(await screen.findByRole('checkbox', { name: /^Dienstanweisungen/ }))
      await user.click(
        within(screen.getByRole('group', { name: 'Typ' })).getByRole('button', { name: /Prompts/ }),
      )

      expect(
        await screen.findByRole('checkbox', { name: /^Formulierungshilfen Referat 50/ }),
      ).toBeInTheDocument()
      expect(screen.queryByRole('checkbox', { name: /^Dienstanweisungen/ })).not.toBeInTheDocument()
      expect(screen.getByText('Ausgewählt: Dienstanweisungen')).toBeInTheDocument()

      await user.click(
        within(screen.getByRole('group', { name: 'Typ' })).getByRole('button', { name: 'Alle' }),
      )
      await user.type(screen.getByRole('searchbox', { name: 'Suche' }), 'gibt es nicht')
      expect(await screen.findByText('Keine Treffer.')).toBeInTheDocument()
      expect(screen.getByText('Ausgewählt: Dienstanweisungen')).toBeInTheDocument()
    })

    it('narrows the tiles to assets from my groups and asks the server for exactly that', async () => {
      const fromMyGroups: Array<string | null> = []
      server.events.on('request:start', ({ request }) => {
        const url = new URL(request.url)
        if (url.pathname === '/api/v1/catalog')
          fromMyGroups.push(url.searchParams.get('fromMyGroups'))
      })
      const user = userEvent.setup()
      renderWithProviders(<SpaceCreatePage />, { withRouter: true })

      await toContentStep(user)
      expect(await screen.findByRole('checkbox', { name: /^Meine Dokumente/ })).toBeVisible()
      await user.click(
        within(screen.getByRole('group', { name: 'Filter' })).getByRole('button', {
          name: 'Aus meinen Gruppen',
        }),
      )

      expect(await screen.findByRole('checkbox', { name: /^Dienstanweisungen/ })).toBeVisible()
      expect(screen.queryByRole('checkbox', { name: /^Meine Dokumente/ })).not.toBeInTheDocument()
      expect(fromMyGroups).toContain('true')
      server.events.removeAllListeners()
    })

    it('narrows the tiles to my favorites', async () => {
      mockFavoriteAssets.add(favoriteKey('KNOWLEDGE_LIBRARY', 'library-dienstanweisungen'))
      const user = userEvent.setup()
      renderWithProviders(<SpaceCreatePage />, { withRouter: true })

      await toContentStep(user)
      expect(await screen.findByRole('checkbox', { name: /^Meine Dokumente/ })).toBeVisible()
      await user.click(
        within(screen.getByRole('group', { name: 'Filter' })).getByRole('button', {
          name: 'Favoriten',
        }),
      )

      expect(await screen.findByRole('checkbox', { name: /^Dienstanweisungen/ })).toBeVisible()
      expect(screen.queryByRole('checkbox', { name: /^Meine Dokumente/ })).not.toBeInTheDocument()
      expect(screen.getAllByRole('checkbox')).toHaveLength(1)
    })

    it('combines favorites and my groups with AND, as the catalog does', async () => {
      mockFavoriteAssets.add(favoriteKey('KNOWLEDGE_LIBRARY', 'library-dienstanweisungen'))
      mockFavoriteAssets.add(favoriteKey('KNOWLEDGE_LIBRARY', 'library-mine'))
      const requested: URLSearchParams[] = []
      server.events.on('request:start', ({ request }) => {
        const url = new URL(request.url)
        if (url.pathname === '/api/v1/catalog') requested.push(url.searchParams)
      })
      const user = userEvent.setup()
      renderWithProviders(<SpaceCreatePage />, { withRouter: true })

      await toContentStep(user)
      expect(await screen.findByRole('checkbox', { name: /^Meine Dokumente/ })).toBeVisible()
      const filters = screen.getByRole('group', { name: 'Filter' })
      await user.click(within(filters).getByRole('button', { name: 'Favoriten' }))
      await user.click(within(filters).getByRole('button', { name: 'Aus meinen Gruppen' }))

      // Favorite but owned in person: out; favorite and from my group: in.
      await waitFor(() =>
        expect(
          screen.queryByRole('checkbox', { name: /^Meine Dokumente/ }),
        ).not.toBeInTheDocument(),
      )
      expect(screen.getByRole('checkbox', { name: /^Dienstanweisungen/ })).toBeVisible()
      expect(
        requested.some(
          (params) => params.get('favorites') === 'true' && params.get('fromMyGroups') === 'true',
        ),
      ).toBe(true)
      server.events.removeAllListeners()
    })

    it('starts with the asset handed over by "In Space verwenden"', async () => {
      mockLocationState = {
        preselect: {
          assetType: 'PROMPT_LIBRARY',
          assetId: 'prompt-library-referat-50',
          name: 'Formulierungshilfen Referat 50',
        },
      }
      const user = userEvent.setup()
      renderWithProviders(<SpaceCreatePage />, { withRouter: true })

      await toContentStep(user)

      expect(
        await screen.findByRole('checkbox', { name: /^Formulierungshilfen Referat 50/ }),
      ).toBeChecked()
    })
  })
})
