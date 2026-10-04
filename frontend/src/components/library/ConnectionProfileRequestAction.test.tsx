import { beforeEach, describe, expect, it } from 'vitest'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { renderWithProviders } from '../../test/test-utils'
import { server } from '../../mocks/server'
import { mockConnectionProfileRequests } from '../../mocks/connectionProfileFixtures'
import { useNotificationStore } from '../../stores/notificationStore'
import type { SourceTypeDescriptor } from '../../types/api'
import ConnectionProfileRequestAction from './ConnectionProfileRequestAction'

const NEXTCLOUD: SourceTypeDescriptor = {
  type: 'NEXTCLOUD',
  displayName: 'Nextcloud',
  indexingRun: true,
  uploads: false,
  pushIntake: false,
  browsable: true,
  profileSupport: 'OPTIONAL',
  profileRequired: false,
  signIns: [{ method: 'PERSONAL_SECRET', ownerships: ['LIBRARY'], secretForm: 'TOKEN' }],
  profileDefaults: [],
  serverAddress: { schemes: ['https', 'http'] },
  creatable: true,
  creatableWithOwnAddress: true,
  locked: false,
}

function lastNotification() {
  return useNotificationStore.getState().queue.at(-1)
}

async function openDialog(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'Zugang für Nextcloud vorschlagen' }))
  return screen.findByRole('dialog', { name: 'Zugang vorschlagen' })
}

describe('ConnectionProfileRequestAction', () => {
  beforeEach(() => {
    useNotificationStore.getState().reset()
  })

  it('sends address and reason for the preset type and names the system administration', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfileRequestAction descriptor={NEXTCLOUD} idPrefix="test" />)

    const dialog = await openDialog(user)
    expect(dialog).toHaveTextContent('Quellart: Nextcloud')
    await user.type(within(dialog).getByLabelText(/Server-Adresse/), 'https://cloud.neu.example/')
    await user.type(within(dialog).getByLabelText(/Begründung/), 'Für das Projekt')
    await user.click(within(dialog).getByRole('button', { name: 'Vorschlagen' }))

    await waitFor(() => expect(lastNotification()?.severity).toBe('success'))
    expect(lastNotification()?.message).toMatch(/bei der Systemverwaltung eingegangen/)
    const sent = mockConnectionProfileRequests.at(-1)
    expect(sent).toMatchObject({
      sourceType: 'NEXTCLOUD',
      serverUrl: 'https://cloud.neu.example',
      reason: 'Für das Projekt',
    })
    const mine = await screen.findByRole('list', { name: 'Ihre Zugangswünsche' })
    expect(within(mine).getByText('https://cloud.neu.example')).toBeVisible()
  }, 15000)

  it('says so when the same request is already open', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfileRequestAction descriptor={NEXTCLOUD} idPrefix="test" />)

    const dialog = await openDialog(user)
    await user.type(
      within(dialog).getByLabelText(/Server-Adresse/),
      'https://cloud.partner.example',
    )
    await user.click(within(dialog).getByRole('button', { name: 'Vorschlagen' }))

    await waitFor(() => expect(lastNotification()?.message).toMatch(/bereits gestellt/))
    expect(mockConnectionProfileRequests).toHaveLength(1)
  }, 15000)

  it('keeps the dialog open with the reason of a 429', async () => {
    server.use(
      http.post('/api/v1/connection-profile-requests', () =>
        HttpResponse.json(
          {
            error:
              'Sie haben in der letzten Stunde bereits 5 Zugangswünsche gestellt. Bitte versuchen Sie es später erneut.',
            status: 429,
          },
          { status: 429, headers: { 'Retry-After': '1200' } },
        ),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfileRequestAction descriptor={NEXTCLOUD} idPrefix="test" />)

    const dialog = await openDialog(user)
    await user.type(within(dialog).getByLabelText(/Server-Adresse/), 'https://cloud.viel.example')
    await user.click(within(dialog).getByRole('button', { name: 'Vorschlagen' }))

    expect(await screen.findByTestId('test-request-error')).toHaveTextContent(
      'bereits 5 Zugangswünsche gestellt',
    )
    expect(screen.getByRole('dialog', { name: 'Zugang vorschlagen' })).toBeVisible()
    expect(lastNotification()).toBeUndefined()
  }, 15000)

  it('shows the own requests of the type with their state and answer, as plain text', async () => {
    mockConnectionProfileRequests.push({
      id: 'declined',
      sourceType: 'NEXTCLOUD',
      serverUrl: 'https://cloud.abgelehnt.example',
      reason: null,
      state: 'DECLINED',
      requestedByName: 'Dev User',
      createdAt: '2026-10-04T08:00:00Z',
      resolvedAt: '2026-10-04T09:00:00Z',
      profile: null,
      answer: '<b>Nutzen Sie den Zugang intern</b>',
    })
    renderWithProviders(<ConnectionProfileRequestAction descriptor={NEXTCLOUD} idPrefix="test" />)

    const mine = await screen.findByRole('list', { name: 'Ihre Zugangswünsche' })
    expect(within(mine).getByText('Offen')).toBeVisible()
    expect(within(mine).getByText('Abgelehnt')).toBeVisible()
    expect(mine).toHaveTextContent(
      'Antwort der Systemverwaltung: <b>Nutzen Sie den Zugang intern</b>',
    )
    expect(mine.querySelector('b')).toBeNull()
  })
})
