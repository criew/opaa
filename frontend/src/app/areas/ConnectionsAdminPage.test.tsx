import { screen, within } from '@testing-library/react'
import { Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { renderWithProviders } from '../../test/test-utils'
import { useAuthStore } from '@/shared/session/authStore'
import ConnectionsAdminPage from './ConnectionsAdminPage'

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

function render(route: string) {
  return renderWithProviders(
    <Routes>
      <Route path="/admin/connections/:tab" element={<ConnectionsAdminPage />} />
    </Routes>,
    { withRouter: true, initialRoute: route },
  )
}

/** Die Anbindungen (#2504): Rollenschranke, Reiter als Routen und der Inhalt je Reiter. */
describe('ConnectionsAdminPage', () => {
  it('zeigt einem Konto ohne Systemverwaltung nur den Hinweis', () => {
    signInAs('USER')
    render('/admin/connections/access')

    expect(screen.getByText(/Für Ihr Konto ist diese Seite nicht freigegeben/)).toBeVisible()
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument()
  })

  it('führt die vier Reiter als Links in der Reihenfolge der Arbeit', () => {
    signInAs('SYSTEM_ADMIN')
    render('/admin/connections/access')

    const tabs = within(
      screen.getByRole('tablist', { name: 'Bereiche der Anbindungen' }),
    ).getAllByRole('tab')
    expect(tabs.map((tab) => tab.textContent)).toEqual([
      'Zugänge',
      'Quellarten',
      'Externe KI-Werkzeuge',
      'Einstellungen',
    ])
    expect(tabs.map((tab) => tab.getAttribute('href'))).toEqual([
      '/admin/connections/access',
      '/admin/connections/source-types',
      '/admin/connections/external-tools',
      '/admin/connections/settings',
    ])
    expect(tabs[0]).toHaveAttribute('aria-selected', 'true')
  })

  it.each([
    ['access', 'button', 'Neuer Zugang'],
    ['source-types', 'region', 'Zustand der Quellarten'],
    ['external-tools', 'region', 'So kommt ein Werkzeug an Inhalte'],
    ['settings', 'region', 'Ausgangsproxy'],
  ] as const)('zeigt im Reiter %s seinen Inhalt', async (tab, role, name) => {
    signInAs('SYSTEM_ADMIN')
    render(`/admin/connections/${tab}`)

    expect(await screen.findByRole(role, { name })).toBeInTheDocument()
  })

  it('führt einen unbekannten Reiter auf die Zugänge statt auf einen anderen Inhalt', async () => {
    signInAs('SYSTEM_ADMIN')
    render('/admin/connections/zugaenge')

    expect(await screen.findByRole('button', { name: 'Neuer Zugang' })).toBeVisible()
    expect(screen.getByRole('tab', { name: 'Zugänge' })).toHaveAttribute('aria-selected', 'true')
  })
})
