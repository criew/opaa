import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useEffect, useRef } from 'react'
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router'
import { renderWithProviders } from '@/test/test-utils'
import AppShell from './AppShell'
import { useAuthStore } from '@/shared/session/authStore'
import { useUiStore } from '@/shared/stores/uiStore'
import PageHeading from '@/shared/ui/a11y/PageHeading'
import { KEEP_FOCUS_STATE } from '@/shared/ui/a11y/routeFocus'
import { SPACE_SEARCH_HANDOVER } from '@/features/space'
import GlobalAreaLayout from './GlobalAreaLayout'
import { useSpaceListLoader } from '@/features/asset'
import { loadSpaceList } from '../themeSeams'

const ADMIN_SECTIONS = [{ label: 'Benutzer & Gruppen', to: '/admin/groups' }]

function renderShell(initialRoute = '/chat') {
  return renderWithProviders(
    <Routes>
      <Route element={<AppShell />}>
        <Route path="/chat" element={<div>Chat-Inhalt</div>} />
        {/* Mirrors App.tsx - overview and create wizard render beside the space column. */}
        <Route path="/spaces" element={<div>Spaces-Karten</div>} />
        <Route path="/spaces/new" element={<div>Space-Assistent</div>} />
        <Route element={<GlobalAreaLayout />}>
          {/* Mirrors App.tsx - /settings renders inside the sectionless global frame,
              the harness had drifted from the route table. */}
          <Route path="/settings" element={<PageHeading title="Einstellungen" />} />
          <Route path="/catalog" element={<div>Ohne Überschrift</div>} />
        </Route>
        <Route element={<GlobalAreaLayout title="Administration" sections={ADMIN_SECTIONS} />}>
          <Route path="/admin/groups" element={<div>Gruppen-Inhalt</div>} />
        </Route>
      </Route>
    </Routes>,
    { withRouter: true, initialRoute },
  )
}

// jsdom has no matchMedia, so MUI would render the mobile drawer (closed, aria-hidden) and hide
// the navigation from role queries. Pretend to be a desktop viewport for these tests.
function matchMediaWith(matcher: (query: string) => boolean) {
  return (query: string): MediaQueryList => ({
    matches: matcher(query),
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })
}

const mobileMatchMedia = matchMediaWith(() => false)

function desktopMatchMedia(query: string): MediaQueryList {
  return {
    matches: query.includes('min-width'),
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  }
}

describe('AppShell', () => {
  const originalMatchMedia = window.matchMedia

  beforeAll(() => {
    window.matchMedia = desktopMatchMedia
  })

  afterAll(() => {
    window.matchMedia = originalMatchMedia
  })

  it('renders the rail destinations and the space column side by side (#786)', () => {
    renderShell()
    expect(screen.getByText('Chats')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Chat' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Katalog' })).toBeInTheDocument()
  })

  it('renders OPAA branding', () => {
    renderShell()
    expect(screen.getAllByText('OPAA').length).toBeGreaterThan(0)
  })

  // No footer - product name and version are in the profile menu under „Info zu …". A
  // contentinfo landmark therefore does not exist.
  it('exposes the landmarks: global rail, chats navigation, main - and no footer', () => {
    renderShell()
    expect(screen.getByRole('navigation', { name: 'Globale Navigation' })).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Chats' })).toBeInTheDocument()
    expect(screen.getByRole('complementary', { name: 'Space-Bereich' })).toBeInTheDocument()
    expect(screen.getByRole('main')).toBeInTheDocument()
    expect(screen.queryByRole('contentinfo')).not.toBeInTheDocument()
  })

  it('offers the skip link as the first focusable element', async () => {
    const user = userEvent.setup()
    renderShell()

    await user.tab()

    expect(screen.getByRole('link', { name: 'Zum Inhalt springen' })).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(screen.getByRole('main')).toHaveFocus()
  })

  it('focuses the page heading after a route change', async () => {
    const user = userEvent.setup()
    renderShell()
    expect(document.body).toHaveFocus()

    // Einstellungen lives behind the rail's avatar.
    useAuthStore.setState({
      user: { id: 'u1', email: 'a@b.example', displayName: 'A. Tester', systemRole: 'USER' },
    })
    await user.click(await screen.findByRole('button', { name: 'Profil und Einstellungen' }))
    await user.click(screen.getByRole('menuitem', { name: 'Einstellungen' }))

    expect(screen.getByRole('heading', { level: 1, name: 'Einstellungen' })).toHaveFocus()
    expect(document.title).toBe('Einstellungen · OPAA')
  })

  it('drops the space column in a global area - rail and frame carry the navigation (#787)', () => {
    renderShell('/admin/groups')

    expect(screen.getByRole('navigation', { name: 'Globale Navigation' })).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Administration' })).toBeInTheDocument()
    expect(screen.getByText('Gruppen-Inhalt')).toBeInTheDocument()
    expect(screen.queryByRole('complementary', { name: 'Space-Bereich' })).not.toBeInTheDocument()
  })

  it('drops the space column on the settings page as well (#788)', () => {
    renderShell('/settings')

    expect(screen.getByRole('navigation', { name: 'Globale Navigation' })).toBeInTheDocument()
    expect(screen.queryByRole('complementary', { name: 'Space-Bereich' })).not.toBeInTheDocument()
  })

  // Guidelines 5.13: the space search opens in the content area, the column with spaces and
  // chats stays - the overview no longer swaps it for the frame of a global area.
  it.each([
    ['/spaces', 'Spaces-Karten'],
    ['/spaces/', 'Spaces-Karten'],
    ['/spaces/new', 'Space-Assistent'],
  ])('keeps the space column beside %s', (route, content) => {
    renderShell(route)

    expect(screen.getByText(content)).toBeInTheDocument()
    expect(screen.getByRole('complementary', { name: 'Space-Bereich' })).toBeInTheDocument()
  })

  it('drops the space column in the catalog as well (#789)', () => {
    renderShell('/catalog')

    expect(screen.getByText('Ohne Überschrift')).toBeInTheDocument()
    expect(screen.queryByRole('complementary', { name: 'Space-Bereich' })).not.toBeInTheDocument()
  })

  it('narrows the mobile drawer to the rail in a global area (#787)', async () => {
    window.matchMedia = mobileMatchMedia
    try {
      renderShell('/admin/groups')

      act(() => useUiStore.setState({ sidebarOpen: true }))

      expect(await screen.findByRole('navigation', { name: 'Globale Navigation' })).toBeVisible()
      expect(screen.queryByRole('complementary', { name: 'Space-Bereich' })).not.toBeInTheDocument()
    } finally {
      window.matchMedia = desktopMatchMedia
    }
  })

  it('shows rail and space column in the mobile drawer and closes it after navigating', async () => {
    // The drawer must carry both navigation levels and dismiss itself once a link
    // inside it navigates instead of staying on top of the new page.
    window.matchMedia = mobileMatchMedia
    try {
      const user = userEvent.setup()
      renderShell()

      act(() => useUiStore.setState({ sidebarOpen: true }))

      const rail = await screen.findByRole('navigation', { name: 'Globale Navigation' })
      expect(rail).toBeVisible()
      expect(screen.getByRole('complementary', { name: 'Space-Bereich' })).toBeVisible()
      // The open drawer is a dialog and needs a name (axe aria-dialog-name).
      expect(screen.getByRole('dialog', { name: 'Navigation' })).toContainElement(rail)

      await user.click(within(rail).getByText('Katalog'))

      await waitFor(() => expect(useUiStore.getState().sidebarOpen).toBe(false))
    } finally {
      window.matchMedia = desktopMatchMedia
    }
  })

  describe('focus when the mobile drawer closes', () => {
    beforeAll(() => {
      window.matchMedia = mobileMatchMedia
    })

    afterAll(() => {
      window.matchMedia = desktopMatchMedia
    })

    // The drawer keeps the page aria-hidden until its exit transition has finished.
    async function waitForDrawerClosed() {
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
      expect(useUiStore.getState().sidebarOpen).toBe(false)
    }

    async function openDrawer(user: ReturnType<typeof userEvent.setup>) {
      await user.click(screen.getByRole('button', { name: 'Menü öffnen' }))
      return screen.findByRole('dialog', { name: 'Navigation' })
    }

    // regression guard for #3300: a destination in the drawer takes the focus to the new page,
    // not back to the drawer's trigger.
    it('moves the focus to the destination after navigating from the drawer', async () => {
      const user = userEvent.setup()
      renderShell()

      const drawer = await openDrawer(user)
      await user.click(within(drawer).getByRole('link', { name: 'Katalog' }))

      await waitForDrawerClosed()
      // The catalog stand-in has no heading, so the focus parks on main.
      expect(screen.getByRole('main')).toHaveFocus()
      expect(screen.getByRole('button', { name: 'Menü öffnen' })).not.toHaveFocus()
    })

    it('lets a handover from the drawer keep the focus the new page placed', async () => {
      let navigateTo: ReturnType<typeof useNavigate> = () => undefined
      function NavigateHandle() {
        navigateTo = useNavigate()
        return null
      }
      function SearchPage() {
        const field = useRef<HTMLInputElement>(null)
        useEffect(() => field.current?.focus(), [])
        return <input ref={field} aria-label="Spaces durchsuchen" />
      }
      const user = userEvent.setup()
      renderWithProviders(
        <>
          <NavigateHandle />
          <Routes>
            <Route element={<AppShell />}>
              <Route path="/chat" element={<div>Chat-Inhalt</div>} />
              <Route path="/spaces" element={<SearchPage />} />
            </Route>
          </Routes>
        </>,
        { withRouter: true, initialRoute: '/chat' },
      )

      await openDrawer(user)
      act(() => {
        void navigateTo('/spaces', { state: SPACE_SEARCH_HANDOVER })
      })

      await waitForDrawerClosed()
      expect(screen.getByRole('textbox', { name: 'Spaces durchsuchen' })).toHaveFocus()
    })

    it('hands the focus back to "Menü öffnen" when the drawer is dismissed with Escape', async () => {
      const user = userEvent.setup()
      renderShell()

      await openDrawer(user)
      await user.keyboard('{Escape}')

      await waitForDrawerClosed()
      expect(screen.getByRole('button', { name: 'Menü öffnen' })).toHaveFocus()
    })
  })

  // regression guard for #2135: a new chat receiving its id stays the same view - the person keeps
  // typing in the chat input.
  it('leaves the focus alone on a navigation that keeps the view', async () => {
    function SameView() {
      const navigate = useNavigate()
      return (
        <>
          <PageHeading title="Chat" />
          <input
            aria-label="Eingabe"
            onChange={() => navigate('/chat/kept', { replace: true, state: KEEP_FOCUS_STATE })}
          />
        </>
      )
    }
    const user = userEvent.setup()
    renderWithProviders(
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/chat/*" element={<SameView />} />
        </Route>
      </Routes>,
      { withRouter: true, initialRoute: '/chat' },
    )

    await user.click(screen.getByLabelText('Eingabe'))
    await user.keyboard('a')

    await waitFor(() => expect(screen.getByLabelText('Eingabe')).toHaveFocus())
    expect(screen.getByRole('heading', { level: 1, name: 'Chat' })).not.toHaveFocus()
  })

  // The marker stays in the history entry; returning to it via back/forward is a page change again.
  it('moves the focus to the heading when going back to an entry that kept the focus', async () => {
    function BackView() {
      const navigate = useNavigate()
      return (
        <>
          <PageHeading title="Chat" />
          <button type="button" onClick={() => navigate(-1)}>
            Zurück
          </button>
        </>
      )
    }
    const user = userEvent.setup()
    renderWithProviders(
      <MemoryRouter
        initialEntries={[{ pathname: '/chat/a', state: KEEP_FOCUS_STATE }, '/chat/b']}
        initialIndex={1}
      >
        <Routes>
          <Route element={<AppShell />}>
            <Route path="/chat/*" element={<BackView />} />
          </Route>
        </Routes>
      </MemoryRouter>,
    )

    await user.click(screen.getByRole('button', { name: 'Zurück' }))

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1, name: 'Chat' })).toHaveFocus(),
    )
  })

  it('falls back to focusing main when the new page has no heading yet', async () => {
    const user = userEvent.setup()
    renderShell()

    await user.click(
      within(screen.getByRole('navigation', { name: 'Globale Navigation' })).getByText('Katalog'),
    )

    expect(screen.getByRole('main')).toHaveFocus()
  })

  it('provides the space list to every page it renders', () => {
    function SpaceListProbe() {
      const loader = useSpaceListLoader()
      return <div>{loader === loadSpaceList ? 'Space-Liste bereitgestellt' : 'andere Liste'}</div>
    }
    renderWithProviders(
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/catalog" element={<SpaceListProbe />} />
        </Route>
      </Routes>,
      { withRouter: true, initialRoute: '/catalog' },
    )

    expect(screen.getByText('Space-Liste bereitgestellt')).toBeInTheDocument()
  })
})
