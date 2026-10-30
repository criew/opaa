import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { renderWithProviders } from '@/test/test-utils'
import { useAuthStore } from '@/shared/session/authStore'
import type { SystemRole } from '@/shared/session/types'
import { resetAuditorRoleRequests } from '../mocks/auditorRoleRequestHandlers'
import AuditorRoleRequestsPage from './AuditorRoleRequestsPage'

function signInAs(systemRole: SystemRole): void {
  useAuthStore.setState({
    user: { id: 'me', email: null, displayName: 'Revision', systemRole },
    isAuthenticated: true,
  })
}

describe('AuditorRoleRequestsPage', () => {
  beforeEach(() => {
    resetAuditorRoleRequests()
  })

  it('lists the open request with the person, the address and who asked for it', async () => {
    signInAs('AUDITOR')
    renderWithProviders(<AuditorRoleRequestsPage />)

    expect(await screen.findByText('Lena Klein')).toBeInTheDocument()
    expect(screen.getByText('lena.klein@stadt.example')).toBeInTheDocument()
    expect(screen.getByText('Systemverwaltung Muster')).toBeInTheDocument()
    expect(screen.getByText('Lokales Konto')).toBeInTheDocument()
    expect(
      screen.getByText('Von der Systemverwaltung geändert am 27.10.2026 – mit der Person prüfen'),
    ).toBeInTheDocument()
  })

  it('confirms a request, shows the setup link once and the list without it', async () => {
    signInAs('AUDITOR')
    const user = userEvent.setup()
    renderWithProviders(<AuditorRoleRequestsPage />)

    await user.click(
      await screen.findByRole('button', { name: 'Antrag für Lena Klein bestätigen' }),
    )

    expect(await screen.findByTestId('auditor-setup-link-value')).toHaveTextContent(
      '/set-password?token=mock-revision-token',
    )
    await user.click(screen.getByRole('button', { name: 'Schließen' }))
    expect(screen.queryByTestId('auditor-setup-link-value')).not.toBeInTheDocument()
    expect(await screen.findByText('Es liegt kein Antrag vor.')).toBeInTheDocument()
  })

  it('shows the refusal of the backend as a sentence', async () => {
    signInAs('AUDITOR')
    server.use(
      http.post('/api/v1/audit/auditor-role-requests/:id/confirm', () =>
        HttpResponse.json(
          {
            status: 409,
            code: 'LAST_LOGIN_CAPABLE_ADMIN',
            error: 'Es muss eine anmeldefähige Systemverwaltung bleiben.',
            timestamp: '2026-10-29T10:00:00Z',
          },
          { status: 409 },
        ),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<AuditorRoleRequestsPage />)

    await user.click(
      await screen.findByRole('button', { name: 'Antrag für Lena Klein bestätigen' }),
    )

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('anmeldefähige Systemverwaltung'),
    )
  })

  it('keeps the page to the revision', () => {
    signInAs('SYSTEM_ADMIN')
    renderWithProviders(<AuditorRoleRequestsPage />)

    expect(screen.getByText('Rollenanträge entscheidet die Revision.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /bestätigen/ })).not.toBeInTheDocument()
  })
})
