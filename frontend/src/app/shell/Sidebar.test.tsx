import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi, beforeEach } from 'vitest'
import { MemoryRouter, Route, Routes } from 'react-router'
import { render } from '@testing-library/react'
import { ThemeProvider } from '@mui/material/styles'
import CssBaseline from '@mui/material/CssBaseline'
import { createAppTheme } from '@/shared/theme/theme'
import Sidebar from './Sidebar'
import { useAuthStore } from '@/shared/session/authStore'
import { useChatStore } from '@/features/chat'
import { useChatListStore } from '@/features/chat'
import { SPACE_SEARCH_HANDOVER, useSpaceStore } from '@/features/space'
import {
  SIDEBAR_DEFAULT_WIDTH,
  SIDEBAR_MAX_WIDTH,
  SIDEBAR_MIN_WIDTH,
  useUiStore,
} from '@/shared/stores/uiStore'
import { OPAA_BRANDING, useBrandingStore } from '@/shared/branding/brandingStore'

const mockNavigate = vi.fn()

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  }
})

const theme = createAppTheme('dark')

/** Eight spaces - more than the menu shows, which makes the limit testable at all. */
function manySpaces() {
  return Array.from({ length: 8 }, (_, index) => ({
    id: `space-${index}`,
    name: `Space ${index}`,
    description: '',
    isDefault: index === 0,
    archived: false,
    memberCount: 1,
    memberships: { groupCount: 0, userCount: 1 },
    userRole: 'ADMIN' as const,
    createdAt: '2026-03-01T10:00:00Z',
    updatedAt: '2026-03-01T10:00:00Z',
  }))
}

/**
 * Renders Sidebar as the element of a real, pathless layout route nested under a matched child
 * route - mirroring how it sits inside AppShell in production (a sibling of the routed page, not
 * itself a route with a :spaceId path). Unlike mocking useParams directly, this exercises React
 * Router's actual param propagation: matchRouteBranch (react-router/lib/router/utils.js) merges
 * every matched segment's params into one object and assigns that same object to every match in
 * the branch, including the pathless layout match - so useParams() in Sidebar genuinely sees the
 * leaf route's :spaceId.
 */
function renderSidebarAtRoute(initialPath: string, sidebar = <Sidebar />) {
  return render(
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route element={sidebar}>
            <Route path="spaces/:spaceId/chats/:chatId" element={null} />
            <Route path="spaces/:spaceId/settings/:tab" element={null} />
            <Route path="spaces/:spaceId" element={null} />
            <Route path="*" element={null} />
          </Route>
        </Routes>
      </MemoryRouter>
    </ThemeProvider>,
  )
}

describe('Sidebar', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
    // The usage order lives in localStorage and would otherwise outlast the test cases.
    window.localStorage.clear()
    useUiStore.setState({ sidebarWidth: SIDEBAR_DEFAULT_WIDTH })
    useBrandingStore.setState({ branding: OPAA_BRANDING })
    useChatStore.setState({
      spaceId: null,
      chatId: null,
      messages: [],
      isLoading: false,
      isLoadingChat: false,
      error: null,
    })
    useChatListStore.setState({ chatsBySpaceId: {}, isLoading: false, error: null })
    useAuthStore.setState({
      user: {
        id: 'user-1',
        email: 'b.wagner@example.de',
        displayName: 'B. Wagner',
        systemRole: 'USER',
      },
      isAuthenticated: true,
    })
    useSpaceStore.setState({
      spaces: [
        {
          id: 'space-personal',
          name: 'Privater Bereich',
          description: null,
          isDefault: true,
          archived: false,
          memberCount: 1,
          memberships: { groupCount: 0, userCount: 1 },
          userRole: 'ADMIN',
          createdAt: '2026-03-01T10:00:00Z',
          updatedAt: '2026-03-01T10:00:00Z',
        },
        {
          id: 'space-engineering',
          name: 'Engineering',
          description: 'Dokumente der Entwicklung',
          isDefault: false,
          archived: false,
          memberCount: 3,
          memberships: { groupCount: 1, userCount: 2 },
          userRole: 'ADMIN',
          createdAt: '2026-03-01T10:00:00Z',
          updatedAt: '2026-03-01T10:00:00Z',
        },
      ],
      isLoadingList: false,
    })
  })

  it('renders the target-design structure: space switcher and chats, nothing global (#786)', () => {
    renderSidebarAtRoute('/chat')
    // The switcher carries the active (here: default) space's name.
    expect(screen.getByRole('button', { name: /Privater Bereich/ })).toBeInTheDocument()
    expect(screen.getByText('Chats')).toBeInTheDocument()
    // (mockup 2a) the column is purely space-scoped: brand mark, catalog,
    // admin destinations and the user badge all live on the global rail instead.
    expect(screen.queryByText('OPAA')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Wissensbibliotheken' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Benutzermenü' })).not.toBeInTheDocument()
    // The column's only settings link is the gear of the space, not the account's.
    expect(
      screen.getByRole('link', { name: 'Einstellungen von „Privater Bereich“' }),
    ).toHaveAttribute('href', '/spaces/space-personal/settings/general')
  })

  // regression guard for #2062: the chat list's live region ("15 weitere Chats angezeigt",
  // visuallyHidden -> position: absolute) needs a positioned ancestor inside the scrolling nav.
  // Without one its static position below a long, expanded list escapes the nav's clip, makes
  // the page taller than the viewport and lets the browser scroll the whole shell up. jsdom has
  // no layout, so this asserts the containment itself (same pattern as MessageList).
  it('contains the chat list in a positioned scroll container', () => {
    renderSidebarAtRoute('/chat')
    const chats = screen.getByRole('navigation', { name: 'Chats' })
    expect(getComputedStyle(chats).overflowY).toBe('auto')
    expect(getComputedStyle(chats).position).toBe('relative')
  })

  it('renders New Chat button for the default space', async () => {
    renderSidebarAtRoute('/chat')
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Neuer Chat' })).toBeInTheDocument()
    })
  })

  it('creates a new chat in the default space and navigates to it when clicked', async () => {
    const user = userEvent.setup()
    renderSidebarAtRoute('/chat')

    await user.click(await screen.findByRole('button', { name: 'Neuer Chat' }))

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith(
        expect.stringMatching(/^\/spaces\/space-personal\/chats\/.+$/),
      )
    })
  })

  it('lists the active space chats loaded from the API', async () => {
    renderSidebarAtRoute('/chat')
    expect(await screen.findByText('Architektur des Projekts')).toBeInTheDocument()
    expect(await screen.findByText('Deployment-Fragen')).toBeInTheDocument()
  })

  it('follows the space selected in the space overview, not the space of the still-open chat (#556)', async () => {
    // The user has an open chat in the personal space (chatStore.spaceId), but has just clicked a
    // different space in the Spaces overview - the route now points at that other space.
    useChatStore.setState({ spaceId: 'space-personal' })

    renderSidebarAtRoute('/spaces/space-engineering')

    expect(await screen.findByText('Unbenannter Chat')).toBeInTheDocument()
    expect(screen.queryByText('Architektur des Projekts')).not.toBeInTheDocument()
    expect(screen.queryByText('Deployment-Fragen')).not.toBeInTheDocument()
  })

  it('falls back to the space of the still-open chat on routes without a :spaceId (#556 review, nit 2)', async () => {
    // No :spaceId on /chat - the chat still open in the engineering space should keep
    // determining the list, not the default (personal) space.
    useChatStore.setState({ spaceId: 'space-engineering' })

    renderSidebarAtRoute('/chat')

    expect(await screen.findByText('Unbenannter Chat')).toBeInTheDocument()
    expect(screen.queryByText('Architektur des Projekts')).not.toBeInTheDocument()
    expect(screen.queryByText('Deployment-Fragen')).not.toBeInTheDocument()
  })

  it('opens the space switcher listing the spaces with their members, without a kind', async () => {
    const user = userEvent.setup()
    renderSidebarAtRoute('/chat')

    await user.click(screen.getByRole('button', { name: /Privater Bereich/ }))

    expect(screen.getByText('Zuletzt genutzt')).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: /Engineering/ })).toBeInTheDocument()
    expect(screen.getByText('1 Gruppe, 2 Personen')).toBeInTheDocument()
    expect(screen.getByText('nur Sie')).toBeInTheDocument()
    expect(screen.queryByText(/Team|Persönlich/)).not.toBeInTheDocument()
  })

  /**
   * The menu stays short so that the two actions below it remain visible - and it
   * starts with the most recently used space.
   */
  it('shows at most five spaces, most recently used first', async () => {
    useSpaceStore.setState({ spaces: manySpaces(), isLoadingList: false })
    window.localStorage.setItem('opaa.spaces.recent', JSON.stringify(['space-6', 'space-3']))

    const user = userEvent.setup()
    renderSidebarAtRoute('/chat')
    // Without a :spaceId in the route the switcher shows the space last active.
    await user.click(screen.getByRole('button', { name: /Space 6/ }))

    const names = screen
      .getAllByRole('menuitem')
      .map((item) => item.textContent ?? '')
      .filter((text) => text.startsWith('Space '))
    expect(names).toHaveLength(5)
    expect(names[0]).toContain('Space 6')
    expect(names[1]).toContain('Space 3')

    // The two actions are always in the menu regardless.
    expect(screen.getByRole('menuitem', { name: 'Alle Spaces anzeigen' })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Neuer Space' })).toBeInTheDocument()
  })

  /**
   * A space opened via „Alle Spaces" appears in the menu at once - without a reload.
   * The usage order is written when the route is entered; if the menu read it only when
   * mounting, it would keep showing the old five.
   */
  it('shows a space opened outside the menu immediately, without a reload', async () => {
    useSpaceStore.setState({ spaces: manySpaces(), isLoadingList: false })

    const user = userEvent.setup()
    renderSidebarAtRoute('/spaces/space-7')
    await user.click(screen.getByRole('button', { name: /Space 7/ }))

    const first = screen
      .getAllByRole('menuitem')
      .find((item) => (item.textContent ?? '').startsWith('Space '))
    expect(first).toHaveTextContent('Space 7')
    // The check mark on the active entry - an MUI icon without a role of its own, hence via the
    // markup.
    expect(first?.querySelector('svg[data-testid="CheckIcon"]')).not.toBeNull()
  })

  /** What is remembered is the route's space, not the fallback to the personal one. */
  it('remembers the space named by the route, not the fallback', () => {
    renderSidebarAtRoute('/chat')
    expect(window.localStorage.getItem('opaa.spaces.recent')).toBeNull()

    renderSidebarAtRoute('/spaces/space-engineering')
    expect(JSON.parse(window.localStorage.getItem('opaa.spaces.recent') ?? '[]')).toEqual([
      'space-engineering',
    ])
  })

  it('opens an empty chat in a space chosen in the switcher', async () => {
    const user = userEvent.setup()
    renderSidebarAtRoute('/chat')

    await user.click(screen.getByRole('button', { name: /Privater Bereich/ }))
    await user.click(screen.getByRole('menuitem', { name: /Engineering/ }))

    expect(mockNavigate).toHaveBeenCalledWith('/spaces/space-engineering/chats/new')
  })

  // An archived space rejects new chats server-side (ChatService) and ChatList disables its
  // "Neu" button, so it must land on its overview rather than on a draft whose first
  // message would fail.
  it('sends an archived space chosen in the switcher to its overview instead', async () => {
    useSpaceStore.setState({
      spaces: [
        {
          id: 'space-personal',
          name: 'Privater Bereich',
          description: null,
          isDefault: true,
          archived: false,
          memberCount: 1,
          memberships: { groupCount: 0, userCount: 1 },
          userRole: 'ADMIN',
          createdAt: '2026-03-01T10:00:00Z',
          updatedAt: '2026-03-01T10:00:00Z',
        },
        {
          id: 'space-archived',
          name: 'Stillgelegt',
          description: 'Abgeschlossenes Vorhaben',
          isDefault: false,
          archived: true,
          memberCount: 2,
          memberships: { groupCount: 0, userCount: 2 },
          userRole: 'ADMIN',
          createdAt: '2026-03-01T10:00:00Z',
          updatedAt: '2026-03-01T10:00:00Z',
        },
      ],
      isLoadingList: false,
    })
    const user = userEvent.setup()
    renderSidebarAtRoute('/chat')

    await user.click(screen.getByRole('button', { name: /Privater Bereich/ }))
    await user.click(screen.getByRole('menuitem', { name: /Stillgelegt/ }))

    expect(mockNavigate).toHaveBeenCalledWith('/spaces/space-archived')
  })

  it('navigates to the spaces overview via the switcher', async () => {
    const user = userEvent.setup()
    renderSidebarAtRoute('/chat')

    await user.click(screen.getByRole('button', { name: /Privater Bereich/ }))
    await user.click(screen.getByRole('menuitem', { name: 'Alle Spaces anzeigen' }))

    // Opens the space search with the cursor in its field (guidelines 5.13).
    expect(mockNavigate).toHaveBeenCalledWith('/spaces', { state: SPACE_SEARCH_HANDOVER })
  })

  it('navigates to the create wizard via the switcher', async () => {
    const user = userEvent.setup()
    renderSidebarAtRoute('/chat')

    await user.click(screen.getByRole('button', { name: /Privater Bereich/ }))
    await user.click(screen.getByRole('menuitem', { name: 'Neuer Space' }))

    expect(mockNavigate).toHaveBeenCalledWith('/spaces/new')
  })

  // The gear beside the switcher is the one entry point into the space's settings; the column
  // has no foot of its own any more.
  it('puts the gear of the active space beside the switcher, without a column foot', async () => {
    const user = userEvent.setup()
    renderSidebarAtRoute('/spaces/space-engineering')

    const gear = screen.getByRole('link', { name: 'Einstellungen von „Engineering“' })
    expect(gear).toHaveAttribute('href', '/spaces/space-engineering/settings/general')
    await user.hover(gear)
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Einstellungen')
    expect(screen.queryByRole('navigation', { name: 'Space-Navigation' })).not.toBeInTheDocument()
    expect(
      screen.queryByRole('link', { name: 'Datenquellen dieses Space' }),
    ).not.toBeInTheDocument()
  })

  /**
   * The entry points to the first tab but is highlighted on every tab -
   * then `aria-current="true"` announces the area match, „page" only the own target
   * (docs/design/accessibility.md 2.3, as on the global rail).
   */
  it('meldet den Einstellungs-Einstieg auf jedem Reiter als aktiv', () => {
    const name = 'Einstellungen von „Engineering“'
    const { unmount } = renderSidebarAtRoute('/spaces/space-engineering/settings/general')
    expect(screen.getByRole('link', { name })).toHaveAttribute('aria-current', 'page')
    unmount()

    renderSidebarAtRoute('/spaces/space-engineering/settings/members')
    expect(screen.getByRole('link', { name })).toHaveAttribute('aria-current', 'true')
  })

  /**
   * Every member sees the entry point; whoever may not manage sees the settings
   * read-only.
   */
  it('shows the settings entry to a plain member and to a curator', () => {
    useSpaceStore.setState({
      spaces: [
        {
          id: 'space-engineering',
          name: 'Engineering',
          description: 'Dokumente der Entwicklung',
          isDefault: false,
          archived: false,
          memberCount: 3,
          memberships: { groupCount: 0, userCount: 3 },
          userRole: 'MEMBER',
          createdAt: '2026-03-01T10:00:00Z',
          updatedAt: '2026-03-01T10:00:00Z',
        },
      ],
      isLoadingList: false,
    })
    const { unmount } = renderSidebarAtRoute('/spaces/space-engineering')
    expect(screen.getByRole('link', { name: 'Einstellungen von „Engineering“' })).toHaveAttribute(
      'href',
      '/spaces/space-engineering/settings/general',
    )
    unmount()

    useSpaceStore.setState({
      spaces: [
        {
          id: 'space-engineering',
          name: 'Engineering',
          description: 'Dokumente der Entwicklung',
          isDefault: false,
          archived: false,
          memberCount: 3,
          memberships: { groupCount: 0, userCount: 3 },
          userRole: 'CURATOR',
          createdAt: '2026-03-01T10:00:00Z',
          updatedAt: '2026-03-01T10:00:00Z',
        },
      ],
      isLoadingList: false,
    })
    renderSidebarAtRoute('/spaces/space-engineering')
    expect(
      screen.getByRole('link', { name: 'Einstellungen von „Engineering“' }),
    ).toBeInTheDocument()
  })

  describe('fixed column on the desktop (guidelines 5.13)', () => {
    afterEach(() => useUiStore.setState({ collapsedSidebarSections: [] }))

    it('lists the spaces as rows instead of the switcher, the active space marked', () => {
      renderSidebarAtRoute('/spaces/space-engineering', <Sidebar resizable />)

      const spaces = screen.getByRole('navigation', { name: 'Spaces' })
      expect(within(spaces).getByRole('link', { name: 'Engineering' })).toHaveAttribute(
        'aria-current',
        'true',
      )
      expect(within(spaces).getByRole('link', { name: 'Neuer Space' })).toHaveAttribute(
        'href',
        '/spaces/new',
      )
      expect(
        within(spaces).getByRole('link', { name: 'Alle Spaces anzeigen und durchsuchen' }),
      ).toHaveAttribute('href', '/spaces')
      // No switcher (menu button) on the fixed column.
      expect(screen.queryByRole('button', { name: /Engineering/ })).not.toBeInTheDocument()
    })

    it('keeps the spaces fixed on top and lets only the chats scroll', () => {
      renderSidebarAtRoute('/spaces/space-engineering', <Sidebar resizable />)

      const spaces = screen.getByRole('navigation', { name: 'Spaces' })
      expect(getComputedStyle(spaces).flexShrink).toBe('0')
      expect(getComputedStyle(spaces).maxHeight).toBe('45%')
      const chats = screen.getByRole('navigation', { name: 'Chats' })
      expect(getComputedStyle(chats).overflowY).toBe('auto')
      expect(getComputedStyle(chats).position).toBe('relative')
    })

    it('heads the chats with plus and search as icon buttons, the groups counted', async () => {
      renderSidebarAtRoute('/spaces/space-personal', <Sidebar resizable />)

      const chats = screen.getByRole('navigation', { name: 'Chats' })
      expect(within(chats).getByRole('heading', { level: 2, name: 'Chats' })).toBeInTheDocument()
      expect(within(chats).getByRole('button', { name: 'Neuer Chat' })).toBeInTheDocument()
      expect(within(chats).getByRole('button', { name: 'Chats durchsuchen' })).toBeInTheDocument()
      expect(
        await within(chats).findByRole('button', { name: 'Zuletzt verwendet 2' }),
      ).toHaveAttribute('aria-expanded', 'true')
    })

    it('marks the last active space on the overview, where the route names none', () => {
      window.localStorage.setItem('opaa.spaces.recent', JSON.stringify(['space-engineering']))
      useChatStore.setState({ spaceId: 'space-personal' })

      renderSidebarAtRoute('/spaces', <Sidebar resizable />)

      const spaces = screen.getByRole('navigation', { name: 'Spaces' })
      expect(within(spaces).getByRole('link', { name: 'Engineering' })).toHaveAttribute(
        'aria-current',
        'true',
      )
    })
  })

  describe('drawer below md (guidelines 5.13)', () => {
    it('keeps the space switcher and gives the chats the head of the fixed column', async () => {
      renderSidebarAtRoute('/spaces/space-engineering')

      expect(screen.queryByRole('navigation', { name: 'Spaces' })).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: /Engineering/ })).toHaveAttribute(
        'aria-haspopup',
        'menu',
      )
      const chats = screen.getByRole('navigation', { name: 'Chats' })
      expect(within(chats).getByRole('button', { name: 'Neuer Chat' })).toBeInTheDocument()
      expect(within(chats).getByRole('button', { name: 'Chats durchsuchen' })).toBeInTheDocument()
      expect(
        await within(chats).findByRole('button', { name: /^Zuletzt verwendet \d+$/ }),
      ).toBeInTheDocument()
    })
  })

  describe('resizable column (#2085)', () => {
    it('offers no resize handle unless the column is resizable', () => {
      renderSidebarAtRoute('/chat')
      expect(
        screen.queryByRole('separator', { name: 'Breite der Space-Spalte' }),
      ).not.toBeInTheDocument()
    })

    it('widens and narrows the column with the arrow keys, within the bounds', async () => {
      const user = userEvent.setup()
      renderSidebarAtRoute('/chat', <Sidebar resizable />)
      const aside = screen.getByRole('complementary', { name: 'Space-Bereich' })
      const handle = screen.getByRole('separator', { name: 'Breite der Space-Spalte' })
      expect(handle).toHaveAttribute('aria-valuenow', String(SIDEBAR_DEFAULT_WIDTH))

      handle.focus()
      await user.keyboard('{ArrowRight}')
      expect(handle).toHaveAttribute('aria-valuenow', String(SIDEBAR_DEFAULT_WIDTH + 16))
      expect(getComputedStyle(aside).width).toBe(`${SIDEBAR_DEFAULT_WIDTH + 16}px`)

      await user.keyboard('{End}{ArrowRight}')
      expect(useUiStore.getState().sidebarWidth).toBe(SIDEBAR_MAX_WIDTH)
      await user.keyboard('{Home}{ArrowLeft}')
      expect(useUiStore.getState().sidebarWidth).toBe(SIDEBAR_MIN_WIDTH)
    })

    it('follows a drag and returns to the default width on double click', async () => {
      const user = userEvent.setup()
      renderSidebarAtRoute('/chat', <Sidebar resizable />)
      const handle = screen.getByRole('separator', { name: 'Breite der Space-Spalte' })

      await user.pointer([
        { keys: '[MouseLeft>]', target: handle, coords: { clientX: 300 } },
        { target: handle, coords: { clientX: 360 } },
        { keys: '[/MouseLeft]', target: handle },
      ])
      expect(useUiStore.getState().sidebarWidth).toBe(SIDEBAR_DEFAULT_WIDTH + 60)

      // After release, moving over the handle must no longer resize the column.
      await user.pointer({ target: handle, coords: { clientX: 420 } })
      expect(useUiStore.getState().sidebarWidth).toBe(SIDEBAR_DEFAULT_WIDTH + 60)

      await user.dblClick(handle)
      expect(useUiStore.getState().sidebarWidth).toBe(SIDEBAR_DEFAULT_WIDTH)
    })
  })
})
