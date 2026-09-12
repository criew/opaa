import { screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import AreaTabs from './AreaTabs'

const TABS = [
  { value: 'server', label: 'SMTP-Zugang' },
  { value: 'templates', label: 'Vorlagen' },
] as const

function renderTabs(value: 'server' | 'templates', inhalt = vi.fn(() => <p>Inhalt</p>)) {
  renderWithProviders(
    <AreaTabs
      tabs={TABS}
      value={value}
      href={(v) => `/admin/mail/${v}`}
      label="Bereiche der E-Mail-Einstellungen"
      idPrefix="mail"
    >
      {inhalt}
    </AreaTabs>,
    { withRouter: true },
  )
  return inhalt
}

describe('AreaTabs', () => {
  it('offers every area as a link to its own route', () => {
    renderTabs('server')

    const leiste = screen.getByRole('tablist', { name: 'Bereiche der E-Mail-Einstellungen' })
    // Links, keine Klick-Handler: Ein Verweis soll im richtigen Bereich landen, und „in neuem Tab
    // öffnen" muss funktionieren.
    expect(within(leiste).getByRole('tab', { name: 'SMTP-Zugang' })).toHaveAttribute(
      'href',
      '/admin/mail/server',
    )
    expect(within(leiste).getByRole('tab', { name: 'Vorlagen' })).toHaveAttribute(
      'href',
      '/admin/mail/templates',
    )
  })

  it('marks the area of the current route as selected', () => {
    renderTabs('templates')

    expect(screen.getByRole('tab', { name: 'Vorlagen' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'SMTP-Zugang' })).toHaveAttribute(
      'aria-selected',
      'false',
    )
  })

  it('renders the content of the active area only', () => {
    const inhalt = renderTabs('server')

    expect(screen.getByText('Inhalt')).toBeInTheDocument()
    // Ohne diese Regel liefen die Anfragen des unsichtbaren Bereichs im Hintergrund weiter.
    expect(inhalt).toHaveBeenCalledTimes(1)
    expect(inhalt).toHaveBeenCalledWith('server')
  })

  it('points every aria-controls at a panel that exists', () => {
    renderTabs('server')

    for (const reiter of screen.getAllByRole('tab')) {
      const panelId = reiter.getAttribute('aria-controls')
      expect(panelId).toBeTruthy()
      const panel = document.getElementById(panelId!)
      expect(panel).not.toBeNull()
      // Und zurück: Das Panel nennt seinen Reiter, damit die Beziehung in beide Richtungen steht.
      expect(panel!.getAttribute('aria-labelledby')).toBe(reiter.id)
    }
  })

  it('hides the panel of the inactive area', () => {
    renderTabs('server')

    expect(document.getElementById('mail-tabpanel-server')).not.toHaveAttribute('hidden')
    expect(document.getElementById('mail-tabpanel-templates')).toHaveAttribute('hidden')
  })
})
