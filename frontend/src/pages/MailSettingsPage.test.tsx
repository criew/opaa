import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { Route, Routes } from 'react-router'
import { server } from '../mocks/server'
import { mockMailSettings, mockMailTemplates, setMockMailSettings } from '../mocks/mailFixtures'
import { MAIL_FAILING_HOST } from '../mocks/mailHandlers'
import { renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import { useMailStore } from '../stores/mailStore'
import type { MailSettingsUpdateRequest, MailTemplateUpdateRequest } from '../types/api'
import MailSettingsPage from './MailSettingsPage'

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

function renderPage(route = '/admin/mail/server') {
  return renderWithProviders(
    <Routes>
      <Route path="/admin/mail/:tab" element={<MailSettingsPage />} />
    </Routes>,
    { withRouter: true, initialRoute: route },
  )
}

/**
 * Die E-Mail-Einstellungen (#1542, ADR-0033 Entscheidung 10): Rollenschranke, Statusanzeige,
 * Passwortmaske, Testversand mit allen drei Ergebnissen, der Hinweis auf eine fehlende
 * OPAA_PUBLIC_BASE_URL und die Vorlagenverwaltung mit Platzhalterfehler und Zurücksetzen.
 */
describe('MailSettingsPage', () => {
  beforeEach(() => {
    useMailStore.getState().reset()
    vi.spyOn(window, 'confirm').mockReturnValue(true)
  })

  it('zeigt einem Konto ohne Systemverwaltung nur den Hinweis', () => {
    signInAs('USER')
    renderPage()
    expect(screen.getByText(/nicht freigegeben/i)).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: 'Server' })).not.toBeInTheDocument()
  })

  it('zeigt letzten erfolgreichen Versand und letzten Fehler mit Grund', async () => {
    signInAs('SYSTEM_ADMIN')
    renderPage()

    const status = await screen.findByRole('region', { name: 'Versandstatus' })
    expect(within(status).getByText(/Letzter Versand fehlgeschlagen am/)).toBeInTheDocument()
    expect(
      within(status).getAllByText(/Verbindung abgelehnt \(Connection refused\)/).length,
    ).toBeGreaterThan(0)
    expect(within(status).getByText('Letzter erfolgreicher Versand')).toBeInTheDocument()
    expect(within(status).getByText('Letzter Fehler')).toBeInTheDocument()
  })

  it('zeigt das gespeicherte Passwort nie und sendet für ein leeres Feld die Maske', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    let sent: MailSettingsUpdateRequest | null = null
    server.use(
      http.put('/api/v1/system/mail-settings', async ({ request }) => {
        sent = (await request.json()) as MailSettingsUpdateRequest
        return HttpResponse.json({ ...mockMailSettings, host: sent.host })
      }),
    )
    renderPage()

    const host = await screen.findByRole('textbox', { name: 'Server' })
    await waitFor(() => expect(host).toHaveValue('smtp.intern.example'))
    const password = screen.getByLabelText('Passwort')
    expect(password).toHaveValue('')
    expect(password).toHaveAttribute('type', 'password')
    // neither the ciphertext nor the mask is rendered into a field anywhere on the page
    expect(screen.queryByDisplayValue('***')).not.toBeInTheDocument()

    await user.clear(host)
    await user.type(host, 'smtp.neu.example')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    await waitFor(() => expect(sent).not.toBeNull())
    expect(sent!.password).toBe('***')
    expect(sent!.host).toBe('smtp.neu.example')
    expect(sent!.username).toBe('opaa')
  })

  it('meldet einen erfolgreichen Testversand mit der eigenen Adresse', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderPage()

    await screen.findByRole('textbox', { name: 'Server' })
    await user.click(screen.getByRole('button', { name: 'Testmail an mich senden' }))

    expect(
      await screen.findByText('Die Testnachricht wurde an admin@opaa.local versendet.'),
    ).toBeInTheDocument()
  })

  it('meldet einen übersprungenen Testversand mit Grund, wenn SMTP aus ist', async () => {
    signInAs('SYSTEM_ADMIN')
    setMockMailSettings({ ...mockMailSettings, enabled: false })
    const user = userEvent.setup()
    renderPage()

    await screen.findByRole('textbox', { name: 'Server' })
    await user.click(screen.getByRole('button', { name: 'Testmail an mich senden' }))

    expect(
      await screen.findByText(/Es wurde nichts versendet: SMTP ist nicht eingerichtet/),
    ).toBeInTheDocument()
  })

  it('meldet einen fehlgeschlagenen Testversand mit dem Grund des Backends', async () => {
    signInAs('SYSTEM_ADMIN')
    setMockMailSettings({ ...mockMailSettings, host: MAIL_FAILING_HOST })
    const user = userEvent.setup()
    renderPage()

    await screen.findByRole('textbox', { name: 'Server' })
    await user.click(screen.getByRole('button', { name: 'Testmail an mich senden' }))

    expect(
      await screen.findByText(
        'Der Versand ist fehlgeschlagen: Verbindung abgelehnt (Connection refused)',
      ),
    ).toBeInTheDocument()
  })

  it('weist auf eine fehlende öffentliche Basisadresse hin', async () => {
    signInAs('SYSTEM_ADMIN')
    setMockMailSettings({ ...mockMailSettings, publicBaseUrlConfigured: false })
    renderPage()

    expect(await screen.findByText('Öffentliche Adresse fehlt')).toBeInTheDocument()
    expect(screen.getByText(/OPAA_PUBLIC_BASE_URL ist nicht gesetzt/)).toBeInTheDocument()
  })

  it('zeigt den Hinweis nicht, solange die Basisadresse gesetzt ist', async () => {
    signInAs('SYSTEM_ADMIN')
    renderPage()

    await screen.findByRole('textbox', { name: 'Server' })
    expect(screen.queryByText('Öffentliche Adresse fehlt')).not.toBeInTheDocument()
  })

  it('führt die Vorlagen mit Kennzeichen und öffnet eine im Editor', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderPage('/admin/mail/templates')

    const list = await screen.findByRole('navigation', { name: 'Vorlagen' })
    expect(within(list).getAllByRole('button')).toHaveLength(mockMailTemplates.length)
    const entry = within(list).getByText('Einladung eines lokalen Kontos').closest('div')!
    expect(within(entry).getByText('Standard')).toBeInTheDocument()

    await user.click(within(list).getByText('Einladung eines lokalen Kontos'))

    expect(await screen.findByLabelText('Betreff')).toHaveValue('Ihr Zugang zu {{productName}}')
    expect(
      screen.getByRole('button', { name: 'Platzhalter actionUrl einfügen' }),
    ).toBeInTheDocument()
    // sandbox="" - no script, no form, no access to the surrounding document: a template is
    // looked at, not executed
    expect(
      await screen.findByTitle('Vorschau der HTML-Fassung', {}, { timeout: 3000 }),
    ).toHaveAttribute('sandbox', '')
  })

  it('trennt den abgeschalteten vom nicht eingerichteten Zugang', async () => {
    signInAs('SYSTEM_ADMIN')
    setMockMailSettings({ ...mockMailSettings, enabled: false })
    renderPage()

    const status = await screen.findByRole('region', { name: 'Versandstatus' })
    expect(within(status).getByText('Versand ausgeschaltet')).toBeInTheDocument()
    expect(within(status).queryByText('Nicht konfiguriert')).not.toBeInTheDocument()
  })

  it('sperrt den Testversand einer Vorlage, solange der Entwurf ungespeichert ist', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderPage('/admin/mail/templates')

    const list = await screen.findByRole('navigation', { name: 'Vorlagen' })
    await user.click(within(list).getByText('Testnachricht'))
    const subject = await screen.findByLabelText('Betreff')
    expect(screen.getByRole('button', { name: 'Testmail senden' })).toBeEnabled()

    await user.type(subject, ' (Entwurf)')

    expect(screen.getByRole('button', { name: 'Testmail senden' })).toBeDisabled()
    expect(screen.getByText(/Zum Testen zuerst speichern/)).toBeInTheDocument()
  })

  it('weist einen leeren Betreff am Feld ab, statt die Meldung des Backends abzuwarten', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderPage('/admin/mail/templates')

    const list = await screen.findByRole('navigation', { name: 'Vorlagen' })
    await user.click(within(list).getByText('Testnachricht'))
    const subject = await screen.findByLabelText('Betreff')

    await user.clear(subject)

    expect(screen.getByText('Betreff darf nicht leer sein.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Speichern' })).toBeDisabled()
  })

  it('zeigt den Feldfehler des Backends für eine nicht unterstützte Vorlagen-Syntax', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderPage('/admin/mail/templates')

    const list = await screen.findByRole('navigation', { name: 'Vorlagen' })
    await user.click(within(list).getByText('Testnachricht'))
    const subject = await screen.findByLabelText('Betreff')

    await user.clear(subject)
    await user.paste('Hallo {{{displayName}}}')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    expect(
      (await screen.findAllByText(/Nicht unterstützte Vorlagen-Syntax \{\{\{displayName\}\}\}/))
        .length,
    ).toBeGreaterThan(0)
  })

  it('zeigt den Feldfehler des Backends für einen undeklarierten Platzhalter', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderPage('/admin/mail/templates')

    const list = await screen.findByRole('navigation', { name: 'Vorlagen' })
    await user.click(within(list).getByText('Testnachricht'))
    const subject = await screen.findByLabelText('Betreff')

    // paste, not type: userEvent reads "{{" in typed text as the escape for a literal brace
    await user.clear(subject)
    await user.paste('Hallo {{unbekannt}}')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    // verbatim the backend's own message: field name, unknown names, accepted names
    expect(
      (
        await screen.findAllByText(
          /^subject: Unbekannte Platzhalter \{\{unbekannt\}\}\. Erlaubt sind /,
        )
      ).length,
    ).toBeGreaterThan(0)
  })

  it('fragt vor dem Zurücksetzen nach und stellt den Standard wieder her', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderPage('/admin/mail/templates')

    const list = await screen.findByRole('navigation', { name: 'Vorlagen' })
    await user.click(within(list).getByText('Testnachricht'))
    const subject = await screen.findByLabelText('Betreff')
    const original = (subject as HTMLInputElement).value

    await user.clear(subject)
    await user.type(subject, 'Eigener Betreff')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))
    await waitFor(() => expect(within(list).getByText('angepasst')).toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: 'Auf Standard zurücksetzen' }))

    expect(window.confirm).toHaveBeenCalled()
    await waitFor(() => expect(screen.getByLabelText('Betreff')).toHaveValue(original))
    expect(within(list).queryByText('angepasst')).not.toBeInTheDocument()
  })

  it('lässt eine gespeicherte HTML-Fassung beim Speichern unangetastet', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    const stored = mockMailTemplates.find((t) => t.key === 'TEST_MAIL')!
    stored.bodyHtml = '<p>eigene HTML-Fassung</p>'
    let sent: MailTemplateUpdateRequest | null = null
    server.use(
      http.put('/api/v1/system/mail-templates/:templateKey', async ({ request }) => {
        sent = (await request.json()) as MailTemplateUpdateRequest
        return HttpResponse.json({ ...stored, subject: sent.subject, source: 'DATABASE' })
      }),
    )
    renderPage('/admin/mail/templates')

    const list = await screen.findByRole('navigation', { name: 'Vorlagen' })
    await user.click(within(list).getByText('Testnachricht'))
    const subject = await screen.findByLabelText('Betreff')
    await user.type(subject, ' (neu)')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    // the PUT is a full replacement: leaving bodyHtml out would quietly drop the override
    await waitFor(() => expect(sent).not.toBeNull())
    expect(sent!.bodyHtml).toBe('<p>eigene HTML-Fassung</p>')
  })

  it('führt einen unbekannten Bereich auf den SMTP-Zugang zurück', async () => {
    signInAs('SYSTEM_ADMIN')
    renderPage('/admin/mail/gibtesnicht')

    const tabs = await screen.findByRole('tablist', { name: 'Bereiche der E-Mail-Einstellungen' })
    expect(within(tabs).getByRole('tab', { name: 'SMTP-Zugang' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
  })

  it('stellt die beiden Bereiche als eigene Routen bereit', async () => {
    signInAs('SYSTEM_ADMIN')
    renderPage('/admin/mail/templates')

    const tabs = await screen.findByRole('tablist', { name: 'Bereiche der E-Mail-Einstellungen' })
    expect(within(tabs).getByRole('tab', { name: 'Vorlagen' })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    expect(within(tabs).getByRole('tab', { name: 'SMTP-Zugang' })).toHaveAttribute(
      'href',
      '/admin/mail/server',
    )
  })
})
