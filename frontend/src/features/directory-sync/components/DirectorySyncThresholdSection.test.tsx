import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { useNotificationStore } from '@/shared/stores/notificationStore'
import { server } from '@/test/server'
import { answerConfirm, renderWithProviders } from '@/test/test-utils'
import { resetMockDirectorySyncSettings } from '../mocks/directorySyncSettingsHandlers'
import DirectorySyncThresholdSection from './DirectorySyncThresholdSection'

const SETTINGS = '/api/v1/admin/directory-sync/settings'
const FIELD = 'Bestätigung ab (Prozent)'

function captureSaves() {
  const sent: unknown[] = []
  server.events.on('request:start', async ({ request }) => {
    if (request.method === 'PUT') sent.push(await request.clone().json())
  })
  return sent
}

describe('DirectorySyncThresholdSection', () => {
  beforeEach(() => {
    resetMockDirectorySyncSettings()
    server.events.removeAllListeners()
  })

  it('names the factory default while no own value is set', async () => {
    renderWithProviders(<DirectorySyncThresholdSection />)

    expect(await screen.findByLabelText(FIELD)).toHaveValue('30')
    expect(screen.getByText('Werkseinstellung: 30 %')).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: 'Werkseinstellung wiederherstellen' }),
    ).not.toBeInTheDocument()
  })

  it('asks with the strongest tone before a run may withdraw more without confirmation', async () => {
    const sent = captureSaves()
    const user = userEvent.setup()
    renderWithProviders(<DirectorySyncThresholdSection />)

    const field = await screen.findByLabelText(FIELD)
    await user.clear(field)
    await user.type(field, '60')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    const question = 'Plausibilitätsschwelle auf 60 % ändern?'
    expect(await screen.findByRole('dialog', { name: question })).toHaveTextContent(
      'bis zu 60 % entfernen und sperren, ohne dass jemand bestätigt',
    )
    await answerConfirm(user, question, 'Ändern')

    await waitFor(() => expect(sent).toEqual([{ changeThresholdPercent: 60 }]))
    expect(
      await screen.findByText('Eigener Wert: 60 % (Werkseinstellung: 30 %)'),
    ).toBeInTheDocument()
  })

  it('warns that a reset holds back runs that went through so far', async () => {
    server.use(
      http.get(SETTINGS, () =>
        HttpResponse.json({
          changeThresholdPercent: 60,
          defaultChangeThresholdPercent: 30,
          minChangeThresholdPercent: 1,
          maxChangeThresholdPercent: 100,
          overridden: true,
        }),
      ),
    )
    const sent = captureSaves()
    const user = userEvent.setup()
    renderWithProviders(<DirectorySyncThresholdSection />)

    await user.click(
      await screen.findByRole('button', { name: 'Werkseinstellung wiederherstellen' }),
    )
    const question = 'Auf die Werkseinstellung von 30 % zurücksetzen?'
    expect(await screen.findByRole('dialog', { name: question })).toHaveTextContent(
      'Läufe ab 30 % statt ab 60 % warten dann auf Bestätigung',
    )
    await answerConfirm(user, question, 'Werkseinstellung wiederherstellen')

    await waitFor(() => expect(sent).toEqual([{ changeThresholdPercent: null }]))
  })

  it('tells a refused save at the field and as a message', async () => {
    server.use(
      http.put(SETTINGS, () =>
        HttpResponse.json(
          {
            error: 'Abgelehnt',
            fieldErrors: [
              {
                field: 'changeThresholdPercent',
                code: 'OUT_OF_RANGE',
                message: 'Die Plausibilitätsschwelle muss zwischen 1 und 100 Prozent liegen',
              },
            ],
          },
          { status: 400 },
        ),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<DirectorySyncThresholdSection />)

    const field = await screen.findByLabelText(FIELD)
    await user.clear(field)
    await user.type(field, '20')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))
    await answerConfirm(user, 'Plausibilitätsschwelle auf 20 % ändern?', 'Ändern')

    expect(
      await screen.findByText('Die Plausibilitätsschwelle muss zwischen 1 und 100 Prozent liegen'),
    ).toBeInTheDocument()
    expect(field).toHaveAttribute('aria-invalid', 'true')
    expect(useNotificationStore.getState().queue).toContainEqual(
      expect.objectContaining({
        severity: 'error',
        message:
          'Plausibilitätsschwelle nicht gespeichert: Die Plausibilitätsschwelle muss zwischen 1 und 100 Prozent liegen',
      }),
    )
  })

  it.each(['0', '101', '2,5', 'x'])('refuses %s before sending anything', async (typed) => {
    const sent = captureSaves()
    const user = userEvent.setup()
    renderWithProviders(<DirectorySyncThresholdSection />)

    const field = await screen.findByLabelText(FIELD)
    await user.clear(field)
    await user.type(field, typed)

    expect(screen.getByText('Bitte eine ganze Zahl von 1 bis 100 eingeben.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Speichern' })).toBeDisabled()
    expect(sent).toEqual([])
  })
})
