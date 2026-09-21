import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import DirectorySyncPage from './DirectorySyncPage'
import { useAuthStore } from '../stores/authStore'
import { useOidcProviderStore } from '../stores/oidcProviderStore'

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

const providerCard = () =>
  screen.findByRole('region', { name: 'Verzeichnisabgleich Verzeichnisdienst' })

describe('DirectorySyncPage', () => {
  beforeEach(() => {
    signInAs('SYSTEM_ADMIN')
    useOidcProviderStore.getState().reset()
  })

  // ADR-0036, Entscheidung 3: Ein ausstehender Plan ist ein lauter Zustand - mit seinem Alter.
  it('names the age of a pending plan and what it would do', async () => {
    renderWithProviders(<DirectorySyncPage />, { withRouter: true })

    const card = await providerCard()
    expect(within(card).getByText(/Ein Plan wartet seit/)).toBeInTheDocument()
    expect(within(card).getByText(/23 Mitgliedschaften würden entzogen/)).toBeInTheDocument()
    expect(within(card).getByText(/1 Konten gesperrt/)).toBeInTheDocument()
  })

  // #237/#1816: Der Differenzbericht nennt die Mitgliederzahl je Gruppe, bevor etwas angewendet
  // wird - und die Konten, die der Lauf sperren würde (#1818).
  it('shows the diff report of a dry run with the member count per group', async () => {
    renderWithProviders(<DirectorySyncPage />, { withRouter: true })
    const user = userEvent.setup()

    const card = await providerCard()
    await user.click(within(card).getByRole('button', { name: 'Trockenlauf' }))

    // Der ausstehende Plan zeigt denselben Bericht - geprüft wird, dass der Trockenlauf ihn
    // überhaupt darstellt, nicht wie oft er auf der Karte steht.
    expect((await within(card).findAllByText(/Referat 52.*9 Mitglieder/)).length).toBeGreaterThan(0)
    expect(within(card).getAllByText(/Referat 50.*23 Mitglieder/).length).toBeGreaterThan(0)
    expect(within(card).getAllByText(/Bernd Bauer/).length).toBeGreaterThan(0)
  })

  it('applies a pending plan only with a reason', async () => {
    renderWithProviders(<DirectorySyncPage />, { withRouter: true })
    const user = userEvent.setup()

    const card = await providerCard()
    const confirm = await within(card).findByRole('button', { name: 'Plan bestätigen' })
    expect(confirm).toBeDisabled()

    await user.type(within(card).getByLabelText(/grund/i), 'Reorganisation zum 01.10.')
    expect(within(card).getByRole('button', { name: 'Plan bestätigen' })).toBeEnabled()
    await user.click(within(card).getByRole('button', { name: 'Plan bestätigen' }))

    expect(await within(card).findByText(/bestätigte Plan wurde angewendet/)).toBeInTheDocument()
  })

  it('explains the page to an account without the system role', async () => {
    signInAs('USER')
    renderWithProviders(<DirectorySyncPage />, { withRouter: true })

    expect(await screen.findByText(/nicht freigegeben/i)).toBeInTheDocument()
  })
})
