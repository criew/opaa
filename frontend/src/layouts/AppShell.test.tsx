import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import { Route, Routes } from 'react-router'
import { renderWithProviders } from '../test/test-utils'
import AppShell from './AppShell'
import { useAuthStore } from '../stores/authStore'
import { useUiStore } from '../stores/uiStore'
import PageHeading from '../components/a11y/PageHeading'
import GlobalAreaLayout from './GlobalAreaLayout'

const ADMIN_SECTIONS = [{ label: 'Benutzer & Gruppen', to: '/admin/groups' }]

function renderShell(initialRoute = '/chat') {
  return renderWithProviders(
    <Routes>
      <Route element={<AppShell />}>
        <Route path="/chat" element={<div>Chat-Inhalt</div>} />
        <Route path="/settings" element={<PageHeading title="Einstellungen" />} />
        <Route element={<GlobalAreaLayout />}>
          <Route path="/libraries" element={<div>Ohne Überschrift</div>} />
          <Route path="/spaces" element={<div>Spaces-Karten</div>} />
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
    expect(screen.getByRole('link', { name: 'Spaces' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Katalog' })).toBeInTheDocument()
  })

  it('renders OPAA branding', () => {
    renderShell()
    expect(screen.getAllByText('OPAA').length).toBeGreaterThan(0)
  })

  it('exposes the landmarks: global rail, chats navigation, main and contentinfo', () => {
    renderShell()
    expect(screen.getByRole('navigation', { name: 'Globale Navigation' })).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Chats' })).toBeInTheDocument()
    expect(screen.getByRole('complementary', { name: 'Space-Bereich' })).toBeInTheDocument()
    expect(screen.getByRole('main')).toBeInTheDocument()
    expect(screen.getByRole('contentinfo')).toHaveTextContent('OPAA v0.1.0')
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

    // Einstellungen lives behind the rail's avatar since #786 (previously the user badge, #587).
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

  it('drops the space column on the spaces overview - it appears only inside a space (#809)', () => {
    renderShell('/spaces')

    expect(screen.getByText('Spaces-Karten')).toBeInTheDocument()
    expect(screen.queryByRole('complementary', { name: 'Space-Bereich' })).not.toBeInTheDocument()
  })

  it('drops the space column in the library catalog as well (#789)', () => {
    renderShell('/libraries')

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
    // The drawer must carry both navigation levels (#786) and dismiss itself once a link
    // inside it navigates - it used to stay on top of the new page (review #791, finding 9).
    window.matchMedia = mobileMatchMedia
    try {
      const user = userEvent.setup()
      renderShell()

      act(() => useUiStore.setState({ sidebarOpen: true }))

      const rail = await screen.findByRole('navigation', { name: 'Globale Navigation' })
      expect(rail).toBeVisible()
      expect(screen.getByRole('complementary', { name: 'Space-Bereich' })).toBeVisible()

      await user.click(within(rail).getByText('Katalog'))

      await waitFor(() => expect(useUiStore.getState().sidebarOpen).toBe(false))
    } finally {
      window.matchMedia = desktopMatchMedia
    }
  })

  it('falls back to focusing main when the new page has no heading yet', async () => {
    const user = userEvent.setup()
    renderShell()

    await user.click(
      within(screen.getByRole('navigation', { name: 'Globale Navigation' })).getByText('Katalog'),
    )

    expect(screen.getByRole('main')).toHaveFocus()
  })
})
