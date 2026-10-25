import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { server } from '@/test/server'
import { answerConfirm, renderWithProviders } from '@/test/test-utils'
import { resetMockSuccessionSettings } from '../mocks/successionHandlers'
import SuccessionSettingsSection from './SuccessionSettingsSection'

const FIELD = 'Lange offen ab (Monate)'

describe('SuccessionSettingsSection', () => {
  beforeEach(() => {
    resetMockSuccessionSettings()
    server.events.removeAllListeners()
  })

  it('sets an own threshold and has the list read again', async () => {
    const onSaved = vi.fn()
    const user = userEvent.setup()
    renderWithProviders(<SuccessionSettingsSection onSaved={onSaved} />)

    const field = await screen.findByLabelText(FIELD)
    expect(field).toHaveValue('12')
    expect(screen.getByText('Werkseinstellung: 12 Monate')).toBeInTheDocument()
    await user.clear(field)
    await user.type(field, '6')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))
    await answerConfirm(user, 'Einträge ab 6 Monate als lange offen hervorheben?', 'Ändern')

    expect(
      await screen.findByText('Eigener Wert: 6 Monate (Werkseinstellung: 12 Monate)'),
    ).toBeInTheDocument()
    expect(onSaved).toHaveBeenCalledTimes(1)
  })

  it('warns that a reset highlights entries only later', async () => {
    server.use(
      http.get('/api/v1/admin/succession/settings', () =>
        HttpResponse.json({
          agingThresholdMonths: 6,
          defaultAgingThresholdMonths: 12,
          minAgingThresholdMonths: 1,
          maxAgingThresholdMonths: 120,
          overridden: true,
        }),
      ),
    )
    const sent: unknown[] = []
    server.events.on('request:start', async ({ request }) => {
      if (request.method === 'PUT') sent.push(await request.clone().json())
    })
    const user = userEvent.setup()
    renderWithProviders(<SuccessionSettingsSection onSaved={vi.fn()} />)

    await user.click(
      await screen.findByRole('button', { name: 'Werkseinstellung wiederherstellen' }),
    )
    const question = 'Auf die Werkseinstellung von 12 Monate zurücksetzen?'
    expect(await screen.findByRole('dialog', { name: question })).toHaveTextContent(
      'erst nach 12 Monate statt nach 6 Monate hervorgehoben',
    )
    await answerConfirm(user, question, 'Werkseinstellung wiederherstellen')

    await waitFor(() => expect(sent).toEqual([{ agingThresholdMonths: null }]))
  })
})
