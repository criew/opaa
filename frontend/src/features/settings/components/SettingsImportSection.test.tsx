import { afterEach, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '@/test/test-utils'
import ConfirmHost from '@/shared/ui/feedback/ConfirmHost'
import { mockSettings, resetMockSettings } from '../mocks/settingsHandlers'
import { settingsDocumentFixture } from '../mocks/settingsFixtures'
import SettingsImportSection from './SettingsImportSection'

function renderSection() {
  const view = renderWithProviders(
    <>
      <SettingsImportSection />
      <ConfirmHost />
    </>,
  )
  const input = view.container.querySelector('input[type="file"]') as HTMLInputElement
  return { ...view, input }
}

function settingsFile(content: string, name = 'opaa-einstellungen.json') {
  return new File([content], name, { type: 'application/json' })
}

describe('SettingsImportSection', () => {
  afterEach(() => resetMockSettings())

  it('shows the differences first and takes them over only after a confirmation', async () => {
    const user = userEvent.setup()
    const { input } = renderSection()
    const document = settingsDocumentFixture()
    document.sections.auditRetention.retentionMonths = 48

    await user.upload(input, settingsFile(JSON.stringify(document)))

    const table = await screen.findByRole('table', { name: 'Änderungen durch den Import' })
    expect(within(table).getByText('Aufbewahrungsfrist des Protokolls')).toBeInTheDocument()
    expect(within(table).getByText('Aufbewahrungsfrist in Monaten')).toBeInTheDocument()
    expect(within(table).queryByText('Sicherheitsrelevant')).not.toBeInTheDocument()
    expect(within(table).getByText('36')).toBeInTheDocument()
    expect(within(table).getByText('48')).toBeInTheDocument()
    expect(mockSettings.sections.auditRetention.retentionMonths).toBe(36)

    await user.click(screen.getByRole('button', { name: 'Übernehmen' }))
    await user.click(await screen.findByRole('button', { name: 'Übernehmen' }))

    await waitFor(() => expect(mockSettings.sections.auditRetention.retentionMonths).toBe(48))
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('marks a security-relevant change in the preview', async () => {
    const user = userEvent.setup()
    const { input } = renderSection()
    const document = settingsDocumentFixture()
    document.sections.linkTargets.allowedOrigins = ['https://intranet.stadt.example']

    await user.upload(input, settingsFile(JSON.stringify(document)))

    const table = await screen.findByRole('table', { name: 'Änderungen durch den Import' })
    expect(within(table).getByText('Sicherheitsrelevant')).toBeInTheDocument()
    expect(within(table).getByText('https://intranet.stadt.example')).toBeInTheDocument()
    expect(screen.getByText(/Eine Änderung ist sicherheitsrelevant/)).toBeInTheDocument()
  })

  it.each([
    [
      409,
      'Solange OPAA_PUBLIC_BASE_URL nicht gesetzt ist, kann die Selbstregistrierung nicht eingeschaltet werden.',
      [],
    ],
    [
      400,
      'Die Angaben sind ungültig.',
      [
        {
          field: 'mail.host',
          code: 'TOO_LONG',
          message: 'Der SMTP-Server darf höchstens 255 Zeichen lang sein.',
        },
      ],
    ],
  ])(
    'shows the refusal of the import after the confirmation (%i)',
    async (status, error, fieldErrors) => {
      server.use(
        http.post('/api/v1/system/settings/import', () =>
          HttpResponse.json(
            { error, status, timestamp: new Date().toISOString(), fieldErrors },
            { status },
          ),
        ),
      )
      const user = userEvent.setup()
      const { input } = renderSection()
      const document = settingsDocumentFixture()
      document.sections.auditRetention.retentionMonths = 48

      await user.upload(input, settingsFile(JSON.stringify(document)))
      await screen.findByRole('table', { name: 'Änderungen durch den Import' })
      await user.click(screen.getByRole('button', { name: 'Übernehmen' }))
      await user.click(await screen.findByRole('button', { name: 'Übernehmen' }))

      const alert = await screen.findByRole('alert')
      expect(alert).toHaveTextContent(error)
      for (const fieldError of fieldErrors) {
        expect(alert).toHaveTextContent(fieldError.message)
      }
      expect(mockSettings.sections.auditRetention.retentionMonths).toBe(36)
    },
  )

  it.each([
    [413, 'Die Datei ist größer als 256 KiB.'],
    [429, 'Zu viele Anfragen — bitte versuchen Sie es in Kürze erneut.'],
  ])('shows why the backend refuses to check the file (%i)', async (status, error) => {
    server.use(
      http.post('/api/v1/system/settings/import/preview', () =>
        HttpResponse.json({ error, status, timestamp: new Date().toISOString() }, { status }),
      ),
    )
    const user = userEvent.setup()
    const { input } = renderSection()

    await user.upload(input, settingsFile(JSON.stringify(settingsDocumentFixture())))

    expect(await screen.findByRole('alert')).toHaveTextContent(error)
  })

  it('refuses a file over 256 KiB before sending it', async () => {
    const user = userEvent.setup()
    const { input } = renderSection()

    await user.upload(input, settingsFile('x'.repeat(256 * 1024 + 1)))

    expect(await screen.findByRole('alert')).toHaveTextContent('Die Datei ist größer als 256 KiB.')
  })

  it('says so when the file matches the installation', async () => {
    const user = userEvent.setup()
    const { input } = renderSection()

    await user.upload(input, settingsFile(JSON.stringify(settingsDocumentFixture()), 'gleich.json'))

    expect(
      await screen.findByText(/„gleich.json“ stimmt mit dieser Installation überein/),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Übernehmen' })).not.toBeInTheDocument()
  })

  it('refuses a file that is no settings export without asking the backend', async () => {
    const user = userEvent.setup()
    const { input } = renderSection()

    await user.upload(input, settingsFile('{"format": "etwas anderes"}'))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Die Datei ist kein Einstellungsexport von OPAA.',
    )
  })

  it('shows the reason the backend refuses a document for', async () => {
    const user = userEvent.setup()
    const { input } = renderSection()
    const document = { ...settingsDocumentFixture(), sections: { unbekannt: { a: 1 } } }

    await user.upload(input, settingsFile(JSON.stringify(document)))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Diese Installation kennt diese Abschnitte nicht: unbekannt.',
    )
  })
})
