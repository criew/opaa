import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '@/test/test-utils'
import { useAuthStore } from '@/shared/session/authStore'
import SettingsTransferPage from './SettingsTransferPage'

function signInAs(systemRole: 'SYSTEM_ADMIN' | 'USER') {
  useAuthStore.setState({
    mode: 'dev',
    isAuthenticated: true,
    isLoading: false,
    user: { id: 'user-1', email: 'admin@opaa.local', displayName: 'Admin', systemRole },
    token: null,
    error: null,
    userManager: null,
  })
}

describe('SettingsTransferPage', () => {
  it('offers export and import to the system administration', () => {
    signInAs('SYSTEM_ADMIN')
    renderWithProviders(<SettingsTransferPage />)

    expect(
      screen.getByRole('heading', { level: 1, name: 'Einstellungen übertragen' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Einstellungen exportieren' })).toBeInTheDocument()
    expect(screen.getByText('Datei auswählen')).toBeInTheDocument()
  })

  it('shows anyone else only that the page is not theirs', () => {
    signInAs('USER')
    renderWithProviders(<SettingsTransferPage />)

    expect(screen.getByText(/Für Ihr Konto ist diese Seite nicht freigegeben/)).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Einstellungen exportieren' }),
    ).not.toBeInTheDocument()
  })
})
