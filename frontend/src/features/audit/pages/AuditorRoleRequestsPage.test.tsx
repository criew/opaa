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
  })

  it('confirms a request and shows the list without it', async () => {
    signInAs('AUDITOR')
    const user = userEvent.setup()
    renderWithProviders(<AuditorRoleRequestsPage />)

    await user.click(
      await screen.findByRole('button', { name: 'Antrag für Lena Klein bestätigen' }),
    )

    expect(await screen.findByText('Es liegt kein Antrag vor.')).toBeInTheDocument()
  })

  it('shows the refusal of the backend as a sentence', async () => {
    signInAs('AUDITOR')
    server.use(
      http.post('/api/v1/audit/auditor-role-requests/:id/confirm', () =>
        HttpResponse.json(
          {
            status: 409,
            code: 'AUDITOR_ACCOUNT',
            error: 'Ein lokales Konto erhält die Rolle Revision nur mit E-Mail-Versand.',
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
      expect(screen.getByRole('alert')).toHaveTextContent('nur mit E-Mail-Versand'),
    )
  })

  it('keeps the page to the revision', () => {
    signInAs('SYSTEM_ADMIN')
    renderWithProviders(<AuditorRoleRequestsPage />)

    expect(screen.getByText('Rollenanträge entscheidet die Revision.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /bestätigen/ })).not.toBeInTheDocument()
  })
})
