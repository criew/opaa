import { afterEach, describe, expect, it } from 'vitest'
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
    expect(within(table).getByText('36')).toBeInTheDocument()
    expect(within(table).getByText('48')).toBeInTheDocument()
    expect(mockSettings.sections.auditRetention.retentionMonths).toBe(36)

    await user.click(screen.getByRole('button', { name: 'Übernehmen' }))
    await user.click(await screen.findByRole('button', { name: 'Übernehmen' }))

    await waitFor(() => expect(mockSettings.sections.auditRetention.retentionMonths).toBe(48))
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
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
