import { beforeEach, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import { mockMailSettings, setMockMailSettings } from '../mocks/mailFixtures'
import { MAIL_FAILING_HOST } from '../mocks/mailHandlers'
import { useMailStore } from './mailStore'

/**
 * Der Mail-Store (#1542): Laden, Speichern, die Statusfortschreibung nach einem Testversand und
 * die Übernahme der Serverantwort in die Übersichtsliste.
 */
describe('useMailStore', () => {
  beforeEach(() => {
    useMailStore.getState().reset()
  })

  it('lädt die Einstellungen', async () => {
    await useMailStore.getState().loadSettings()
    expect(useMailStore.getState().settings?.host).toBe('smtp.intern.example')
    expect(useMailStore.getState().settingsError).toBeNull()
  })

  it('hält die Fehlermeldung des Backends fest, statt sie zu verschlucken', async () => {
    server.use(
      http.get('/api/v1/system/mail-settings', () =>
        HttpResponse.json({ error: 'Nicht erlaubt' }, { status: 403 }),
      ),
    )
    await useMailStore.getState().loadSettings()
    expect(useMailStore.getState().settings).toBeNull()
    expect(useMailStore.getState().settingsError).toBe('Nicht erlaubt')
  })

  it('liest nach einem fehlgeschlagenen Testversand den Status neu ein', async () => {
    setMockMailSettings({ ...mockMailSettings, host: MAIL_FAILING_HOST, lastFailureAt: null })
    await useMailStore.getState().loadSettings()
    expect(useMailStore.getState().settings?.lastFailureAt).toBeNull()

    const result = await useMailStore.getState().sendTestMail()

    expect(result.outcome).toBe('FAILED')
    expect(useMailStore.getState().settings?.lastFailureAt).not.toBeNull()
  })

  it('zieht die Übersichtsliste beim Speichern einer Vorlage mit', async () => {
    await useMailStore.getState().loadTemplates()
    expect(useMailStore.getState().templates.find((t) => t.key === 'TEST_MAIL')?.source).toBe(
      'DEFAULT',
    )

    await useMailStore.getState().openTemplate('TEST_MAIL')
    await useMailStore.getState().saveTemplate('TEST_MAIL', {
      subject: 'Eigener Betreff',
      bodyPlain: 'Guten Tag {{displayName}},\n\nalles in Ordnung.\n',
    })

    const summary = useMailStore.getState().templates.find((t) => t.key === 'TEST_MAIL')
    expect(summary?.source).toBe('DATABASE')
    expect(summary?.subject).toBe('Eigener Betreff')
  })

  it('lässt die zuletzt geöffnete Vorlage gewinnen, auch wenn ihre Antwort zuerst da ist', async () => {
    const release: Array<() => void> = []
    server.use(
      http.get('/api/v1/system/mail-templates/:templateKey', async ({ params }) => {
        const key = String(params.templateKey)
        // the first request is held back, the second answers at once
        if (key === 'PASSWORD_RESET') {
          await new Promise<void>((resolve) => release.push(resolve))
        }
        return HttpResponse.json({
          key,
          label: key,
          locale: 'de',
          subject: key,
          bodyPlain: 'Text',
          bodyHtml: null,
          source: 'DEFAULT',
          placeholders: [],
          defaultSubject: key,
          defaultBodyPlain: 'Text',
          defaultBodyHtml: '<p>Text</p>',
          updatedAt: null,
          updatedBy: null,
        })
      }),
    )

    const slow = useMailStore.getState().openTemplate('PASSWORD_RESET')
    await useMailStore.getState().openTemplate('TEST_MAIL')
    expect(useMailStore.getState().template?.key).toBe('TEST_MAIL')

    release.forEach((resolve) => resolve())
    await slow

    expect(useMailStore.getState().template?.key).toBe('TEST_MAIL')
  })

  it('meldet einen undeklarierten Platzhalter als Fehler der Vorlage', async () => {
    await useMailStore.getState().openTemplate('TEST_MAIL')
    await expect(
      useMailStore
        .getState()
        .saveTemplate('TEST_MAIL', { subject: 'Hallo {{unbekannt}}', bodyPlain: 'Text' }),
    ).rejects.toThrow(/Unbekannte Platzhalter \{\{unbekannt\}\}/)
    expect(useMailStore.getState().templateError).toMatch(/^subject: Unbekannte Platzhalter/)
  })
})
