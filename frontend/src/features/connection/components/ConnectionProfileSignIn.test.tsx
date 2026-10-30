import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { mockConnectionProfiles } from '../mocks/connectionProfileFixtures'
import { answerConfirm, renderWithProviders } from '@/test/test-utils'
import { useAuthStore } from '@/shared/session/authStore'
import type { SourceTypeDescriptor } from '@/shared/api/types'
import ConnectionProfilesTab from './ConnectionProfilesTab'
import { openProfile, useDesktopViewport } from './connectionTestUtils'

const DRIVE: SourceTypeDescriptor = {
  type: 'GOOGLE_DRIVE',
  displayName: 'Google Drive',
  indexingRun: true,
  uploads: false,
  pushIntake: false,
  browsable: true,
  profileSupport: 'OPTIONAL',
  profileRequired: false,
  signIns: [{ method: 'SERVICE_ACCOUNT_KEY', ownerships: ['LIBRARY'] }],
  profileDefaults: [
    { key: 'subject', label: 'Imitiertes Konto', kind: 'TEXT', choices: [], profileOnly: true },
  ],
  serverAddress: { schemes: [], fixed: 'https://www.googleapis.com' },
  creatable: true,
  creatableWithOwnAddress: true,
  locked: false,
}

const KEY_FILE = JSON.stringify({
  type: 'service_account',
  client_email: 'leser@projekt.iam.gserviceaccount.com',
  private_key_id: 'abc',
  private_key: '-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n',
})

/** A profile's own sign-in in the administration: key upload, rejection and its test. */
/** Chooses the source type of a new profile in the select of the dialog. */
async function chooseSourceType(
  user: ReturnType<typeof userEvent.setup>,
  dialog: HTMLElement,
  name: RegExp,
) {
  await user.click(within(dialog).getByRole('combobox', { name: /Quellart/ }))
  await user.click(await screen.findByRole('option', { name }))
}

describe('ConnectionProfilesTab, sign-in of the profile', () => {
  useDesktopViewport()

  beforeEach(() => {
    useAuthStore.setState({
      mode: 'dev',
      isAuthenticated: true,
      isLoading: false,
      user: {
        id: 'user-1',
        email: 'admin@opaa.local',
        displayName: 'Admin',
        systemRole: 'SYSTEM_ADMIN',
      },
      token: null,
      error: null,
      userManager: null,
    })
    server.use(http.get('/api/v1/source-types', () => HttpResponse.json([DRIVE])))
  })

  afterEach(() => {
    server.events.removeAllListeners()
  })

  it('takes a service account key as a file and asks for no client id', async () => {
    const user = userEvent.setup()
    const sent: Array<Record<string, unknown>> = []
    server.events.on('request:start', async ({ request }) => {
      if (request.method === 'POST' && request.url.endsWith('/admin/connection-profiles')) {
        sent.push((await request.clone().json()) as Record<string, unknown>)
      }
    })
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    await user.click(await screen.findByRole('button', { name: 'Neuer Zugang' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang anlegen' })
    await chooseSourceType(user, dialog, /Google Drive/)
    await user.type(within(dialog).getByLabelText(/^Name/), 'Zugang Drive')
    await user.click(within(dialog).getByLabelText(/^Anmeldeart/))
    await user.click(await screen.findByRole('option', { name: 'Dienstkonto-Schlüssel' }))
    expect(within(dialog).queryByLabelText(/^Client-ID/)).not.toBeInTheDocument()
    await user.upload(
      within(dialog).getByLabelText('Schlüsseldatei des Dienstkontos'),
      new File([KEY_FILE], 'schluessel.json', { type: 'application/json' }),
    )
    expect(await within(dialog).findByTestId('profile-key-status')).toHaveTextContent(
      'leser@projekt.iam.gserviceaccount.com',
    )
    await user.click(within(dialog).getByRole('button', { name: 'Anlegen' }))

    await screen.findByRole('dialog', { name: 'Zugang Drive' })
    expect(sent[0]).toMatchObject({
      authMethod: 'SERVICE_ACCOUNT_KEY',
      clientId: null,
      clientSecret: KEY_FILE,
      serverUrl: 'https://www.googleapis.com',
    })
  }, 20000)

  it('shows a rejected sign-in and lifts it with a sign-in test', async () => {
    const user = userEvent.setup()
    mockConnectionProfiles[0] = {
      ...mockConnectionProfiles[0],
      sourceType: 'GOOGLE_DRIVE',
      authMethod: 'SERVICE_ACCOUNT_KEY',
      clientSecretSet: true,
      signInRejected: true,
    }
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    const row = await screen.findByRole('row', { name: /Zugang Nextcloud intern/ })
    expect(within(row).getByText('Anmeldung abgelehnt')).toBeVisible()
    const panel = await openProfile(user, 'Zugang Nextcloud intern')
    expect(within(panel).getByText('Anmeldung abgelehnt')).toBeVisible()
    await user.click(
      within(panel).getByRole('button', { name: 'Anmeldung von Zugang Nextcloud intern testen' }),
    )

    expect(await screen.findByText('Anmeldung erfolgreich.')).toBeVisible()
    await waitFor(() =>
      expect(within(panel).queryByText('Anmeldung abgelehnt')).not.toBeInTheDocument(),
    )
  })
  it('says before the emergency shutdown that the key of the profile is deleted for good', async () => {
    const user = userEvent.setup()
    mockConnectionProfiles[0] = {
      ...mockConnectionProfiles[0],
      sourceType: 'GOOGLE_DRIVE',
      authMethod: 'SERVICE_ACCOUNT_KEY',
      clientSecretSet: true,
    }
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    const panel = await openProfile(user, 'Zugang Nextcloud intern')
    await user.click(
      within(panel).getByRole('button', {
        name: 'Alle Verbindungen trennen: Zugang Nextcloud intern',
      }),
    )
    const question = await screen.findByRole('dialog', { name: /Alle Verbindungen von/ })
    expect(question).toHaveTextContent(
      'Der Dienstkonto-Schlüssel des Zugangs wird unwiderruflich gelöscht',
    )
    expect(question).toHaveTextContent(
      'wenn die Systemverwaltung einen Schlüssel am Zugang neu hinterlegt',
    )
    expect(question).toHaveTextContent('Beim Anbieter widerruft OPAA nichts')
    expect(question).not.toHaveTextContent('wenn ihre Zugangsdaten neu eingetragen sind')
    await answerConfirm(user, /Alle Verbindungen von/, 'Abbrechen')
  })
})
