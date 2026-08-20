import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest'
import { Route, Routes } from 'react-router'
import { renderWithProviders } from '../test/test-utils'
import AppShell from './AppShell'
import PageHeading from '../components/a11y/PageHeading'

function renderShell(initialRoute = '/chat') {
  return renderWithProviders(
    <Routes>
      <Route element={<AppShell />}>
        <Route path="/chat" element={<div>Chat-Inhalt</div>} />
        <Route path="/settings" element={<PageHeading title="Einstellungen" />} />
        <Route path="/libraries" element={<div>Ohne Überschrift</div>} />
      </Route>
    </Routes>,
    { withRouter: true, initialRoute },
  )
}

// jsdom has no matchMedia, so MUI would render the mobile drawer (closed, aria-hidden) and hide
// the navigation from role queries. Pretend to be a desktop viewport for these tests.
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

  it('renders sidebar navigation links', () => {
    renderShell()
    expect(screen.getByText('Spaces')).toBeInTheDocument()
    expect(screen.getByText('Chats')).toBeInTheDocument()
    expect(screen.getByText('Einstellungen')).toBeInTheDocument()
  })

  it('renders OPAA branding', () => {
    renderShell()
    expect(screen.getAllByText('OPAA').length).toBeGreaterThan(0)
  })

  it('exposes the landmarks navigation, main and contentinfo', () => {
    renderShell()
    expect(screen.getByRole('navigation', { name: 'Hauptnavigation' })).toBeInTheDocument()
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

    await user.click(within(screen.getByRole('navigation')).getByText('Einstellungen'))

    expect(screen.getByRole('heading', { level: 1, name: 'Einstellungen' })).toHaveFocus()
    expect(document.title).toBe('Einstellungen · OPAA')
  })

  it('falls back to focusing main when the new page has no heading yet', async () => {
    const user = userEvent.setup()
    renderShell()

    await user.click(within(screen.getByRole('navigation')).getByText('Wissensbibliotheken'))

    expect(screen.getByRole('main')).toHaveFocus()
  })
})
