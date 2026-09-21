import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { Route, Routes } from 'react-router'
import { renderWithProviders } from '../test/test-utils'
import ProviderGroupWorklistPage from './ProviderGroupWorklistPage'
import { useAuthStore } from '../stores/authStore'
import { useOidcProviderStore } from '../stores/oidcProviderStore'

function signInAsAdmin() {
  useAuthStore.setState({
    mode: 'oidc',
    isAuthenticated: true,
    isLoading: false,
    user: {
      id: 'user-1',
      email: 'admin@opaa.local',
      displayName: 'Admin',
      systemRole: 'SYSTEM_ADMIN',
    },
    token: null,
    error: null,
    providers: [],
    userManager: null,
    activeProviderId: null,
  })
}

function renderWorklist() {
  return renderWithProviders(
    <Routes>
      <Route
        path="/admin/identity-providers/:providerId/groups"
        element={<ProviderGroupWorklistPage />}
      />
    </Routes>,
    {
      withRouter: true,
      initialRoute: '/admin/identity-providers/oidc-provider-beschaeftigte/groups',
    },
  )
}

describe('ProviderGroupWorklistPage', () => {
  beforeEach(() => {
    signInAsAdmin()
    useOidcProviderStore.getState().reset()
  })

  // ADR-0036, Entscheidung 2: Die Arbeitsliste führt je Gruppe ihre Wirkungen und den Ausgang.
  it('lists every group of the provider with its effects', async () => {
    renderWorklist()

    expect(await screen.findByText('Referat 50')).toBeInTheDocument()
    expect(
      screen.getByText(/12 Berechtigungen an 7 Objekten, 2 Space-Mitgliedschaften in 2 Spaces/),
    ).toBeInTheDocument()
    expect(screen.getByText('/Haus A/Abteilung 5/Referat 50')).toBeInTheDocument()
  })

  it('opens the transfer dialog for one group', async () => {
    renderWorklist()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: /wirkungen übertragen/i }))

    expect(await screen.findByRole('dialog')).toHaveTextContent('Wirkungen übertragen')
    expect(screen.getByRole('button', { name: /vorschau erstellen/i })).toBeInTheDocument()
  })
})
