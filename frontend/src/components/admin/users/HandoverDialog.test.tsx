import { afterEach, describe, expect, it, vi } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../../../test/test-utils'
import { useAuthStore } from '../../../stores/authStore'
import { useUserAdminStore } from '../../../stores/userAdminStore'
import type { LocalUserResponse } from '../../../types/api'
import type { SignInProvider } from '../../../types/auth'
import HandoverDialog from './HandoverDialog'

const PROVIDER: SignInProvider = {
  id: 'provider-1',
  displayName: 'Identitätsanbieter der Stadt',
  issuerUri: 'https://idp.stadt.example/realms/beschaeftigte',
  clientId: 'opaa-frontend',
  isDefault: true,
  sortOrder: 0,
}

const USER: LocalUserResponse = {
  id: 'user-1',
  email: 'erika.muster@stadt.example',
  displayName: 'Erika Muster',
  systemRole: 'USER',
  status: 'ACTIVE',
  passwordChangeRequired: false,
  createdReason: 'Sachbearbeitung Bauamt',
  createdAt: '2026-01-01T08:00:00Z',
  activity: 'ACTIVE',
  bootstrap: false,
}

/**
 * ADR-0033, Entscheidung 12: the administration chooses a provider and states a reason - and has no
 * field for an identity anywhere, because the subject comes from the person's own token.
 */
describe('HandoverDialog', () => {
  afterEach(() => {
    vi.restoreAllMocks()
    useAuthStore.setState({ providers: [] })
  })

  function render(providers: SignInProvider[] = [PROVIDER]) {
    useAuthStore.setState({ providers })
    return renderWithProviders(
      <HandoverDialog target={{ user: USER }} onClose={vi.fn()} onLinkDisplayed={vi.fn()} />,
    )
  }

  it('asks for provider and reason only - never for an identity', () => {
    render()

    expect(screen.getByRole('combobox', { name: /Identitätsanbieter/ })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: /Anlass/ })).toBeInTheDocument()
    expect(screen.queryByLabelText(/Subject/i)).not.toBeInTheDocument()
    expect(screen.getByText(/Einen Rückweg gibt es nicht/)).toBeInTheDocument()
    // was eine Übergabe ist und wie sie abläuft, in einfachen Worten
    expect(screen.getByText(/nicht mehr mit einem Passwort an/)).toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(3)
  })

  it('keeps the action disabled until a reason is given', async () => {
    const requestHandover = vi.fn().mockResolvedValue({ emailSent: true })
    useUserAdminStore.setState({ requestHandover })
    render()
    const submit = screen.getByRole('button', { name: 'An Identitätsanbieter übergeben' })
    expect(submit).toBeDisabled()

    await userEvent.type(screen.getByRole('textbox', { name: /Anlass/ }), 'Umstellung auf IdP')
    expect(submit).toBeEnabled()

    await userEvent.click(submit)
    await waitFor(() =>
      expect(requestHandover).toHaveBeenCalledWith('user-1', 'provider-1', 'Umstellung auf IdP'),
    )
  })

  it('hands the link over exactly once when the mail did not go out', async () => {
    useUserAdminStore.setState({
      requestHandover: vi.fn().mockResolvedValue({
        emailSent: false,
        deliveryPath: 'LINK_DISPLAYED',
        handoverUrl: '/handover?token=abc',
      }),
    })
    const onLinkDisplayed = vi.fn()
    useAuthStore.setState({ providers: [PROVIDER] })
    renderWithProviders(
      <HandoverDialog
        target={{ user: USER }}
        onClose={vi.fn()}
        onLinkDisplayed={onLinkDisplayed}
      />,
    )

    await userEvent.type(screen.getByRole('textbox', { name: /Anlass/ }), 'Umstellung auf IdP')
    await userEvent.click(screen.getByRole('button', { name: 'An Identitätsanbieter übergeben' }))

    await waitFor(() => expect(onLinkDisplayed).toHaveBeenCalledWith(USER, '/handover?token=abc'))
  })

  it('says what is missing when no identity provider is enabled', () => {
    render([])

    expect(screen.getByText(/keinen aktivierten Identitätsanbieter/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'An Identitätsanbieter übergeben' })).toBeDisabled()
  })
})
