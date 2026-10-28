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
import { useSpaceStore } from '@/features/space'
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
    // The column's only „Einstellungen" link belongs to the space, not to the account.
    expect(screen.getByRole('link', { name: 'Einstellungen' })).toHaveAttribute(
      'href',
      '/spaces/space-personal/settings/general',
    )
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
      expect(screen.getByRole('button', { name: 'Neu: Chat beginnen' })).toBeInTheDocument()
    })
  })

  it('creates a new chat in the default space and navigates to it when clicked', async () => {
    const user = userEvent.setup()
    renderSidebarAtRoute('/chat')

    await user.click(await screen.findByRole('button', { name: 'Neu: Chat beginnen' }))

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
    await user.click(screen.getByRole('button', { name: /Space 0/ }))

    const names = screen
      .getAllByRole('menuitem')
      .map((item) => item.textContent ?? '')
      .filter((text) => text.startsWith('Space '))
    expect(names).toHaveLength(5)
    expect(names[0]).toContain('Space 6')
    expect(names[1]).toContain('Space 3')

    // The two actions are always in the menu regardless.
    expect(screen.getByRole('menuitem', { name: 'Alle Spaces anzeigen' })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: /Neuen Space anlegen/ })).toBeInTheDocument()
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

    expect(mockNavigate).toHaveBeenCalledWith('/spaces')
  })

  it('navigates to the create wizard via the switcher', async () => {
    const user = userEvent.setup()
    renderSidebarAtRoute('/chat')

    await user.click(screen.getByRole('button', { name: /Privater Bereich/ }))
    await user.click(screen.getByRole('menuitem', { name: 'Neuen Space anlegen' }))

    expect(mockNavigate).toHaveBeenCalledWith('/spaces/new')
  })

  // One entry point at the foot of the column instead of two - „Datenquellen dieses Space" is
  // gone, everything administrative lies behind the settings.
  it('links the column foot to the settings of the active space, and to nothing else', () => {
    renderSidebarAtRoute('/spaces/space-engineering')

    // The landmark must wrap a real list - List component="nav" once replaced the <ul>
    // and left the <li>s parentless, an axe "serious" violation.
    const footNav = screen.getByRole('navigation', { name: 'Space-Navigation' })
    expect(within(footNav).getByRole('list')).toBeInTheDocument()

    expect(within(footNav).getAllByRole('link')).toHaveLength(1)
    expect(screen.getByRole('link', { name: 'Einstellungen' })).toHaveAttribute(
      'href',
      '/spaces/space-engineering/settings/general',
    )
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
    const { unmount } = renderSidebarAtRoute('/spaces/space-engineering/settings/general')
    expect(screen.getByRole('link', { name: 'Einstellungen' })).toHaveAttribute(
      'aria-current',
      'page',
    )
    unmount()

    renderSidebarAtRoute('/spaces/space-engineering/settings/members')
    expect(screen.getByRole('link', { name: 'Einstellungen' })).toHaveAttribute(
      'aria-current',
      'true',
    )
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
    expect(screen.getByRole('link', { name: 'Einstellungen' })).toHaveAttribute(
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
    expect(screen.getByRole('link', { name: 'Einstellungen' })).toBeInTheDocument()
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
  describe('UI lab variants', () => {
    afterEach(() => {
      window.sessionStorage.clear()
      useUiStore.setState({ collapsedSidebarSections: [] })
    })

    it('lists the spaces as rows on the desktop column and keeps the switcher in the drawer', () => {
      window.sessionStorage.setItem('opaa.uiLabVariant', 'a')
      const { unmount } = renderSidebarAtRoute('/spaces/space-engineering', <Sidebar resizable />)
      const spaces = screen.getByRole('navigation', { name: 'Spaces' })
      expect(within(spaces).getByRole('link', { name: /Engineering/ })).toHaveAttribute(
        'aria-current',
        'true',
      )
      expect(within(spaces).getByRole('link', { name: 'Neuen Space anlegen' })).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /Engineering/ })).not.toBeInTheDocument()
      unmount()

      renderSidebarAtRoute('/spaces/space-engineering')
      expect(screen.queryByRole('navigation', { name: 'Spaces' })).not.toBeInTheDocument()
      expect(screen.getByRole('button', { name: /Engineering/ })).toBeInTheDocument()
    })

    it('keeps the active space visible when variant B folds the spaces', async () => {
      window.sessionStorage.setItem('opaa.uiLabVariant', 'b')
      const user = userEvent.setup()
      renderSidebarAtRoute('/spaces/space-engineering', <Sidebar resizable />)

      await user.click(screen.getByRole('button', { name: 'Spaces' }))

      expect(screen.getByRole('button', { name: 'Spaces' })).toHaveAttribute(
        'aria-expanded',
        'false',
      )
      const spaces = screen.getByRole('navigation', { name: 'Spaces' })
      expect(within(spaces).getAllByRole('link', { name: /Engineering|Privater/ })).toHaveLength(1)
    })
  })
})
