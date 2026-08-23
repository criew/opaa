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

  it('marks the exact destination with aria-current="page"', () => {
    renderRailAt('/libraries')

    expect(screen.getByRole('link', { name: 'Katalog' })).toHaveAttribute('aria-current', 'page')
    expect(screen.getByRole('link', { name: 'Spaces' })).not.toHaveAttribute('aria-current')
  })

  it('marks a scope hit below the destination with aria-current="true"', () => {
    // /libraries/lib-1 is in the catalog scope but is not the link's own target - "true" is
    // the accurate token there, "page" would claim the link points at the current page.
    renderRailAt('/libraries/lib-1')

    expect(screen.getByRole('link', { name: 'Katalog' })).toHaveAttribute('aria-current', 'true')
  })

  it('counts an open chat as the Spaces scope', () => {
    renderRailAt('/spaces/space-1/chats/chat-1')

    expect(screen.getByRole('link', { name: 'Spaces' })).toHaveAttribute('aria-current', 'true')
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
    expect(adminLink).toHaveAttribute('aria-current', 'true')
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

  it('renders in the light scheme as well - the navy-900 rail surface', () => {
    // All other tests run the dark app theme; this keeps the light-scheme branch
    // (railRoles) from regressing unseen (review #791).
    render(
      <ThemeProvider theme={createAppTheme('light')}>
        <CssBaseline />
        <MemoryRouter initialEntries={['/spaces']}>
          <GlobalRail />
        </MemoryRouter>
      </ThemeProvider>,
    )

    const rail = screen.getByRole('navigation', { name: 'Globale Navigation' })
    expect(rail).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Spaces' })).toHaveAttribute('aria-current', 'page')
  })

  it('shows the user initial in the avatar', () => {
    renderRailAt('/spaces')

    expect(screen.getByRole('button', { name: 'Profil und Einstellungen' })).toHaveTextContent('B')
  })
})
