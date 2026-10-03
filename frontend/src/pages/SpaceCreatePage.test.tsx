import { describe, expect, it, beforeEach, vi } from 'vitest'
import { screen, within } from '@testing-library/react'
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
      'PRIVATE',
      [],
      false,
      [],
    )
    expect(mockNavigate).toHaveBeenCalledWith('/spaces/space-neu')
  })

  it('#1923: offers the chat cleanup switch, off by default, and passes it on', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SpaceCreatePage />, { withRouter: true })

    const cleanup = screen.getByRole('switch', {
      name: 'Inaktive Chats automatisch archivieren und löschen',
    })
    expect(cleanup).not.toBeChecked()
    await user.type(screen.getByLabelText(/Name/), 'Widerspruchsstelle')
    await user.click(cleanup)
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.click(screen.getByRole('button', { name: 'Weiter' }))

    expect(screen.getByText('werden automatisch archiviert und gelöscht')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Space anlegen' }))

    expect(mockCreateNewSpace).toHaveBeenCalledWith(
      'Widerspruchsstelle',
      '',
      'PRIVATE',
      [],
      true,
      [],
    )
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
    await user.type(screen.getByLabelText('Benutzer'), 'al')
    expect(await screen.findByRole('option', { name: /Alice/ })).toBeInTheDocument()
  })

  async function noteAlice(user: ReturnType<typeof userEvent.setup>) {
    await user.type(screen.getByLabelText(/Name/), 'Widerspruchsstelle')
    await user.click(screen.getByRole('button', { name: 'Weiter' }))
    await user.type(screen.getByLabelText('Benutzer'), 'al')
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
    expect(mockCreateNewSpace).toHaveBeenCalledWith(
      'Widerspruchsstelle',
      '',
      'PRIVATE',
      [],
      false,
      [{ userId: expect.any(String), role: 'MEMBER' }],
    )
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
      expect(mockCreateNewSpace).toHaveBeenCalledWith(
        'Widerspruchsstelle',
        '',
        'PRIVATE',
        [],
        false,
        [],
      )
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
        'PRIVATE',
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

    it('offers search, type and favorites in one row, without a group filter', async () => {
      const user = userEvent.setup()
      renderWithProviders(<SpaceCreatePage />, { withRouter: true })

      await toContentStep(user)
      expect(await screen.findByRole('checkbox', { name: /^Meine Dokumente/ })).toBeVisible()
      const search = screen.getByRole('searchbox', { name: 'Suche' })
      const type = screen.getByRole('group', { name: 'Typ' })
      const filters = screen.getByRole('group', { name: 'Filter' })
      expect(search.compareDocumentPosition(type) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      expect(type.compareDocumentPosition(filters) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
      expect(
        within(filters)
          .getAllByRole('button')
          .map((b) => b.textContent),
      ).toEqual(['Favoriten'])
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
