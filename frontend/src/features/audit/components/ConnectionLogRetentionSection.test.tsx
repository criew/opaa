import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../../mocks/server'
import { answerConfirm, renderWithProviders } from '../../../test/test-utils'
import ConnectionLogRetentionSection from './ConnectionLogRetentionSection'

const RETENTION = '/api/v1/admin/connection-log/retention'

async function setMonths(user: ReturnType<typeof userEvent.setup>, value: string) {
  const field = await screen.findByLabelText('Aufbewahrungsfrist (Monate)')
  await user.clear(field)
  await user.type(field, value)
}

describe('ConnectionLogRetentionSection', () => {
  it('shows the current period', async () => {
    renderWithProviders(<ConnectionLogRetentionSection />)

    expect(await screen.findByLabelText('Aufbewahrungsfrist (Monate)')).toHaveValue(12)
  })

  it.each(['5', '25'])('refuses %s months before sending anything', async (value) => {
    let sent = false
    server.use(
      http.put(RETENTION, () => {
        sent = true
        return HttpResponse.json({})
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<ConnectionLogRetentionSection />)
    await setMonths(user, value)

    expect(screen.getByText('Bitte eine ganze Zahl von 6 bis 24 eingeben.')).toBeInTheDocument()
    const save = screen.getByRole('button', { name: 'Speichern' })
    expect(save).toBeDisabled()
    await user.type(screen.getByLabelText('Aufbewahrungsfrist (Monate)'), '{Enter}')
    expect(sent).toBe(false)
  })

  it.each([
    ['6', /älter als 6 Monate sind, werden mit dem nächsten täglichen Lauf endgültig gelöscht/],
    ['24', /Die längere Frist gilt sofort/],
  ])('saves the bound %s months after a confirmation naming the effect', async (value, effect) => {
    let sent: unknown = null
    server.use(
      http.put(RETENTION, async ({ request }) => {
        sent = await request.json()
        return HttpResponse.json({
          retentionMonths: Number(value),
          lastCutoff: null,
          updatedAt: '2026-10-04T00:00:00Z',
        })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<ConnectionLogRetentionSection />)
    await setMonths(user, value)
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    const question = `Aufbewahrungsfrist des Verbindungsprotokolls auf ${value} Monate ändern?`
    expect(await screen.findByRole('dialog', { name: question })).toHaveTextContent(effect)
    await answerConfirm(user, question, 'Frist ändern')

    await waitFor(() => expect(sent).toEqual({ retentionMonths: Number(value) }))
    expect(
      await screen.findByText(`Die Aufbewahrungsfrist beträgt jetzt ${value} Monate.`),
    ).toBeInTheDocument()
  })

  it('explains a 400 of the backend in German', async () => {
    server.use(
      http.put(RETENTION, () =>
        HttpResponse.json(
          { error: 'retentionMonths: muss kleiner-gleich 24 sein', status: 400, timestamp: 'x' },
          { status: 400 },
        ),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<ConnectionLogRetentionSection />)
    await setMonths(user, '18')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))
    await answerConfirm(user, /auf 18 Monate ändern\?/, 'Frist ändern')

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Die Frist wurde nicht angenommen: Sie muss zwischen 6 und 24 Monaten liegen.',
    )
  })

  it('tells a failed load apart and offers to retry', async () => {
    server.use(
      http.get(RETENTION, () =>
        HttpResponse.json(
          { error: 'Zugriff verweigert', status: 403, timestamp: 'x' },
          { status: 403 },
        ),
      ),
    )
    renderWithProviders(<ConnectionLogRetentionSection />)

    expect(await screen.findByText('Zugriff verweigert')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Erneut laden' })).toBeInTheDocument()
    expect(screen.queryByLabelText('Aufbewahrungsfrist (Monate)')).not.toBeInTheDocument()
  })
})
