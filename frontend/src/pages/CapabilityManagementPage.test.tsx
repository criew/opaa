import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { answerConfirm, renderWithProviders } from '../test/test-utils'
import CapabilityManagementPage from './CapabilityManagementPage'
import { useAuthStore } from '../stores/authStore'

function signInAs(systemRole: 'SYSTEM_ADMIN' | 'USER') {
  useAuthStore.setState({
    mode: 'oidc',
    isAuthenticated: true,
    isLoading: false,
    user: { id: 'user-1', email: 'admin@opaa.local', displayName: 'Admin', systemRole },
    token: null,
    error: null,
    providers: [],
    userManager: null,
    activeProviderId: null,
  })
}

describe('CapabilityManagementPage', () => {
  beforeEach(() => {
    signInAs('SYSTEM_ADMIN')
  })

  // ADR-0036, Entscheidung 5: je Anlegerecht eine Klartextzeile des aktuellen Stands.
  it('shows one plain-text line per capability, including the one nobody holds', async () => {
    renderWithProviders(<CapabilityManagementPage />, { withRouter: true })

    expect(
      await screen.findByText('Alle Konten dürfen Konnektorbibliotheken anlegen.'),
    ).toBeInTheDocument()
    expect(screen.getByText(/Niemand darf interne Gruppen anlegen/)).toBeInTheDocument()
    expect(screen.getAllByText('Alle Konten').length).toBeGreaterThan(0)
  })

  it('withdraws a capability from all accounts after the confirmation', async () => {
    renderWithProviders(<CapabilityManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    const card = await screen.findByRole('region', { name: 'Konnektorbibliotheken anlegen' })
    await user.click(within(card).getByRole('button', { name: /entziehen/i }))
    await answerConfirm(user, '„Konnektorbibliotheken anlegen" entziehen?', 'Entziehen')

    await waitFor(() =>
      expect(
        screen.getByText('Alle Konten dürfen Konnektorbibliotheken anlegen.'),
      ).toBeInTheDocument(),
    )
  })

  it('explains the page to an account without the system role', async () => {
    signInAs('USER')
    renderWithProviders(<CapabilityManagementPage />, { withRouter: true })

    expect(await screen.findByText(/nicht freigegeben/i)).toBeInTheDocument()
  })
})
