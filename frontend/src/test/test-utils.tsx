import type { ReactElement } from 'react'
import { render, screen, within, type RenderOptions } from '@testing-library/react'
import type { UserEvent } from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { ThemeProvider } from '@mui/material/styles'
import CssBaseline from '@mui/material/CssBaseline'
import { createAppTheme } from '../theme/theme'
import ConfirmHost from '../components/ConfirmHost'
import NotificationHost from '../components/NotificationHost'
import { useAuthStore } from '../stores/authStore'
import { useConfirmStore } from '../stores/confirmStore'
import { useNotificationStore } from '../stores/notificationStore'
import type { LocalAccountsConfig, SessionKind } from '../types/auth'
import { LOCAL_ACCOUNTS_DISABLED } from '../types/auth'

const theme = createAppTheme('dark')

interface AppRenderOptions extends RenderOptions {
  initialRoute?: string
  withRouter?: boolean
  /**
   * Set false for a tree that mounts a {@link NotificationHost} of its own (AuthLayout does) - two
   * hosts show the same popup twice, and a test asserting on it would then find two elements.
   * Setting it false is also the only way to prove that a component's popup reaches a host the
   * product actually mounts, rather than the one this helper adds.
   */
  withNotificationHost?: boolean
}

export function renderWithProviders(
  ui: ReactElement,
  {
    initialRoute = '/',
    withRouter = false,
    withNotificationHost = true,
    ...renderOptions
  }: AppRenderOptions = {},
) {
  // Notifications from a previous test would otherwise pop up over this render - the queue is
  // app-global (guidelines 5.9), not scoped to a component tree.
  useNotificationStore.getState().reset()
  // Eine offene Bestaetigung aus einem vorherigen Test haelt sonst ihr Overlay ueber diesem
  // Render und faengt jeden Klick ab; `reset` beantwortet sie zugleich mit `false`.
  useConfirmStore.getState().reset()

  function Wrapper({ children }: { children: React.ReactNode }) {
    const content = (
      <ThemeProvider theme={theme}>
        <CssBaseline />
        {children}
        {/* Mounted app-wide by AppShell; mirrored here so component tests observe the popup
            notifications their interactions raise (guidelines 5.9). */}
        {withNotificationHost && <NotificationHost />}
        {/* Ebenso app-weit von der AppShell montiert (#1610): Ohne ihn liefe ein `confirmAction`
            im Test ins Leere und seine Handlung haenge fuer immer am `await`. */}
        <ConfirmHost />
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

/**
 * Beantwortet das Bestätigungs-Overlay (#1610), das eine folgenreiche Handlung absichert.
 *
 * Die Frage wird mitgegeben und nicht nur die Schaltfläche geklickt: Sie ist der Text, den ein
 * Screenreader beim Öffnen vorliest, und damit das, was den Rückfall auf eine falsche
 * Bestätigung sichtbar macht. Über die Frage ist das Overlay außerdem eindeutig, auch wenn es
 * über einem anderen Dialog liegt.
 *
 * @param verb die Beschriftung der bestätigenden Schaltfläche - „Abbrechen" lehnt ab.
 */
export async function answerConfirm(
  user: UserEvent,
  question: string | RegExp,
  verb: string | RegExp,
) {
  const dialog = await screen.findByRole('dialog', { name: question })
  await user.click(within(dialog).getByRole('button', { name: verb }))
}
