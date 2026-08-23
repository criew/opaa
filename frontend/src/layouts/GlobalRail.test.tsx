import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { MemoryRouter } from 'react-router'
import { ThemeProvider } from '@mui/material/styles'
import CssBaseline from '@mui/material/CssBaseline'
import { createAppTheme } from '../theme/theme'
import GlobalRail from './GlobalRail'
import { useAuthStore } from '../stores/authStore'
import { OPAA_BRANDING, useBrandingStore } from '../stores/brandingStore'

const mockNavigate = vi.fn()

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  }
})

const theme = createAppTheme('dark')

function renderRailAt(initialPath: string) {
  return render(
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <MemoryRouter initialEntries={[initialPath]}>
        <GlobalRail />
      </MemoryRouter>
    </ThemeProvider>,
  )
}

describe('GlobalRail', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
    useBrandingStore.setState({ branding: OPAA_BRANDING })
    useAuthStore.setState({
      user: {
        id: 'user-1',
        email: 'b.wagner@example.de',
        displayName: 'B. Wagner',
        systemRole: 'USER',
      },
      isAuthenticated: true,
    })
  })

  it('renders the global destinations as its own navigation landmark (mockup 2a)', () => {
    renderRailAt('/spaces')

    const rail = screen.getByRole('navigation', { name: 'Globale Navigation' })
    expect(rail).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Spaces' })).toHaveAttribute('href', '/spaces')
    expect(screen.getByRole('link', { name: 'Katalog' })).toHaveAttribute('href', '/libraries')
  })

  it('shows the brand emblem without the product name - the rail has no room for text', () => {
    renderRailAt('/spaces')

    expect(screen.queryByText('OPAA')).not.toBeInTheDocument()
  })

  it('marks the destination of the current scope with aria-current', () => {
    renderRailAt('/libraries/lib-1')

    expect(screen.getByRole('link', { name: 'Katalog' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Spaces' })).not.toHaveAttribute('aria-current')
  })

  it('counts an open chat as the Spaces scope', () => {
    renderRailAt('/spaces/space-1/chats/chat-1')

    expect(screen.getByRole('link', { name: 'Spaces' })).toHaveAttribute('aria-current', 'page')
  })

  it('hides the admin destination from regular users and shows it to system admins', () => {
    const { unmount } = renderRailAt('/spaces')
    expect(screen.queryByRole('link', { name: 'Admin' })).not.toBeInTheDocument()
    unmount()

    useAuthStore.setState({
      user: {
        id: 'user-2',
        email: 'admin@example.de',
        displayName: 'Admin',
        systemRole: 'SYSTEM_ADMIN',
      },
      isAuthenticated: true,
    })
    renderRailAt('/admin/branding')
    const adminLink = screen.getByRole('link', { name: 'Admin' })
    expect(adminLink).toHaveAttribute('href', '/admin/groups')
    // Any /admin page counts as the admin scope, not only the link's own target.
    expect(adminLink).toHaveAttribute('aria-current', 'page')
  })

  it('offers Einstellungen and Abmelden behind the avatar (mockup 2a/2c)', async () => {
    const user = userEvent.setup()
    renderRailAt('/spaces')

    await user.click(screen.getByRole('button', { name: 'Profil und Einstellungen' }))

    expect(screen.getByRole('menuitem', { name: 'Einstellungen' })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Abmelden' })).toBeInTheDocument()

    await user.click(screen.getByRole('menuitem', { name: 'Einstellungen' }))
    expect(mockNavigate).toHaveBeenCalledWith('/settings')
  })

  it('shows the user initial in the avatar', () => {
    renderRailAt('/spaces')

    expect(screen.getByRole('button', { name: 'Profil und Einstellungen' })).toHaveTextContent('B')
  })
})
