import type { ReactElement } from 'react'
import { render, type RenderOptions } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { ThemeProvider } from '@mui/material/styles'
import CssBaseline from '@mui/material/CssBaseline'
import { createAppTheme } from '../theme/theme'
import NotificationHost from '../components/NotificationHost'
import { useAuthStore } from '../stores/authStore'
import { useNotificationStore } from '../stores/notificationStore'
import type { LocalAccountsConfig, SessionKind } from '../types/auth'
import { LOCAL_ACCOUNTS_DISABLED } from '../types/auth'

const theme = createAppTheme('dark')

interface AppRenderOptions extends RenderOptions {
  initialRoute?: string
  withRouter?: boolean
}

export function renderWithProviders(
  ui: ReactElement,
  { initialRoute = '/', withRouter = false, ...renderOptions }: AppRenderOptions = {},
) {
  // Notifications from a previous test would otherwise pop up over this render - the queue is
  // app-global (guidelines 5.9), not scoped to a component tree.
  useNotificationStore.getState().reset()

  function Wrapper({ children }: { children: React.ReactNode }) {
    const content = (
      <ThemeProvider theme={theme}>
        <CssBaseline />
        {children}
        {/* Mounted app-wide by AppShell; mirrored here so component tests observe the popup
            notifications their interactions raise (guidelines 5.9). */}
        <NotificationHost />
      </ThemeProvider>
    )

    if (withRouter) {
      return <MemoryRouter initialEntries={[initialRoute]}>{content}</MemoryRouter>
    }

    return content
  }

  return render(ui, { wrapper: Wrapper, ...renderOptions })
}

interface MockAuthStateOptions {
  /** Which kind of session the tab holds (ADR-0033); dev mode has none. */
  sessionKind?: SessionKind | null
  passwordChangeRequired?: boolean
  localAccounts?: LocalAccountsConfig
}

/** Puts the auth store into an authenticated dev-mode state, bypassing any network call. */
export function setMockAuthState({
  sessionKind = null,
  passwordChangeRequired = false,
  localAccounts = LOCAL_ACCOUNTS_DISABLED,
}: MockAuthStateOptions = {}) {
  useAuthStore.setState({
    mode: 'dev',
    isAuthenticated: true,
    isLoading: false,
    user: null,
    token: null,
    error: null,
    userManager: null,
    sessionKind,
    passwordChangeRequired,
    passwordChangeReason: null,
    localAccounts,
  })
}
