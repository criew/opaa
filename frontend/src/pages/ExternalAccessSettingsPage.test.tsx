import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import {
  DEFAULT_SERVER_INSTRUCTIONS,
  mockExternalAccessSettings,
  resetMockExternalAccessSettings,
} from '../mocks/externalAccessHandlers'
import { renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import ExternalAccessSettingsPage from './ExternalAccessSettingsPage'

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

/**
 * Die Kanaleinstellungen der Fremdzugänge (#1717): Rollenschranke, Voreinstellung „aus“ samt
 * Hausnetz, der Hinweis auf die Personalvertretung, das sichtbare Änderungsdatum, das Speichern
 * einer Änderung, das Zurücksetzen des Einleitungstextes und die abgewiesene Netzangabe.
 */
describe('ExternalAccessSettingsPage', () => {
  beforeEach(() => {
    resetMockExternalAccessSettings()
  })

  it('zeigt einem Konto ohne Systemverwaltung nur den Hinweis', () => {
    signInAs('USER')
    renderWithProviders(<ExternalAccessSettingsPage />)

    expect(screen.getByText(/nicht freigegeben/i)).toBeInTheDocument()
    expect(screen.queryByRole('switch', { name: /Fremdzugänge erlauben/ })).not.toBeInTheDocument()
  })

  it('zeigt eine frische Installation als ausgeschaltet, mit Hausnetz und Änderungsdatum', async () => {
    signInAs('SYSTEM_ADMIN')
    renderWithProviders(<ExternalAccessSettingsPage />)

    const switchControl = await screen.findByRole('switch', {
      name: /Fremdzugänge erlauben — derzeit aus/,
    })
    expect(switchControl).not.toBeChecked()
    expect(screen.getByText(/zuletzt geändert am 18\.09\.2026/)).toBeInTheDocument()
    expect(screen.getByLabelText(/Ablauf-Obergrenze/)).toHaveValue(90)
    expect(screen.getByLabelText('Netzbereiche des Kanals')).toHaveValue(
      '10.0.0.0/8\n172.16.0.0/12\n192.168.0.0/16\n127.0.0.0/8\n::1/128',
    )
    expect(screen.getByLabelText('Einleitungstext')).toHaveValue(DEFAULT_SERVER_INSTRUCTIONS)
  })

  it('nennt die Beteiligung der Personalvertretung und die Folgen des Ausschaltens', async () => {
    signInAs('SYSTEM_ADMIN')
    renderWithProviders(<ExternalAccessSettingsPage />)

    expect(await screen.findByText(/Beteiligung der Personalvertretung/)).toBeInTheDocument()
    expect(screen.getByText(/Die Tokens bleiben erhalten/)).toBeInTheDocument()
  })

  it('schaltet den Kanal ein und speichert die geänderten Werte', async () => {
    const user = userEvent.setup()
    signInAs('SYSTEM_ADMIN')
    renderWithProviders(<ExternalAccessSettingsPage />)

    const switchControl = await screen.findByRole('switch', { name: /Fremdzugänge erlauben/ })
    await user.click(switchControl)
    const lifetime = screen.getByLabelText(/Ablauf-Obergrenze/)
    await user.clear(lifetime)
    await user.type(lifetime, '30')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    expect(await screen.findByText(/wurden gespeichert/)).toBeInTheDocument()
    expect(mockExternalAccessSettings.enabled).toBe(true)
    expect(mockExternalAccessSettings.tokenMaxLifetimeDays).toBe(30)
    await waitFor(() => expect(screen.getByText(/durch Dev Admin/)).toBeInTheDocument())
  })

  it('setzt den Einleitungstext auf den Vorgabetext zurück', async () => {
    const user = userEvent.setup()
    signInAs('SYSTEM_ADMIN')
    renderWithProviders(<ExternalAccessSettingsPage />)

    const instructions = await screen.findByLabelText('Einleitungstext')
    await user.clear(instructions)
    await user.type(instructions, 'Eigener Text.')
    expect(instructions).toHaveValue('Eigener Text.')

    await user.click(screen.getByRole('button', { name: 'Vorgabetext' }))

    expect(instructions).toHaveValue(DEFAULT_SERVER_INSTRUCTIONS)
  })

  it('zeigt eine ungültige Netzangabe am Feld statt sie zu speichern', async () => {
    const user = userEvent.setup()
    signInAs('SYSTEM_ADMIN')
    renderWithProviders(<ExternalAccessSettingsPage />)

    const cidrs = await screen.findByLabelText('Netzbereiche des Kanals')
    await user.clear(cidrs)
    await user.type(cidrs, 'stadt.example')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    expect(await screen.findByText(/Keine gültige Netzangabe: stadt.example/)).toBeInTheDocument()
    expect(mockExternalAccessSettings.allowedCidrs).toContain('10.0.0.0/8')
  })
})
