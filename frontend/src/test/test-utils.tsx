import type { ReactElement } from 'react'
import { render, type RenderOptions } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { ThemeProvider } from '@mui/material/styles'
import CssBaseline from '@mui/material/CssBaseline'
import { createAppTheme } from '../theme/theme'
import { useAuthStore } from '../stores/authStore'

const theme = createAppTheme('dark')

interface AppRenderOptions extends RenderOptions {
  initialRoute?: string
  withRouter?: boolean
}

export function renderWithProviders(
  ui: ReactElement,
  { initialRoute = '/', withRouter = false, ...renderOptions }: AppRenderOptions = {},
) {
  function Wrapper({ children }: { children: React.ReactNode }) {
    const content = (
      <ThemeProvider theme={theme}>
        <CssBaseline />
        {children}
      </ThemeProvider>
    )

    if (withRouter) {
      return <MemoryRouter initialEntries={[initialRoute]}>{content}</MemoryRouter>
    }

    return content
  }

  return render(ui, { wrapper: Wrapper, ...renderOptions })
}

export function setMockAuthState() {
  useAuthStore.setState({
    mode: 'mock',
    isAuthenticated: true,
    isLoading: false,
    user: null,
    token: null,
    error: null,
    userManager: null,
  })
}
