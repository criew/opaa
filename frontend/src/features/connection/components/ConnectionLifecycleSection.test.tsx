import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { answerConfirm, renderWithProviders } from '@/test/test-utils'
import type {
  ConnectionLifecycleSettingResponse,
  ConnectionLifecycleSettingsResponse,
} from '@/shared/api/types'
import ConnectionLifecycleSection from './ConnectionLifecycleSection'

const PATH = '/api/v1/admin/connection-lifecycle-settings'

const inactivity: ConnectionLifecycleSettingResponse = {
  setting: 'INACTIVITY_THRESHOLD_DAYS',
  value: 90,
  defaultValue: 90,
  minValue: 30,
  maxValue: 365,
  overridden: false,
  updatedAt: null,
}
const deletion: ConnectionLifecycleSettingResponse = {
  setting: 'PRIVATE_LIBRARY_DELETION_DAYS',
  value: 14,
  defaultValue: 30,
  minValue: 1,
  maxValue: 90,
  overridden: true,
  updatedAt: '2026-10-20T08:00:00Z',
}
const warning: ConnectionLifecycleSettingResponse = {
  setting: 'EXPIRED_CONNECTION_WARNING_THRESHOLD',
  value: 10,
  defaultValue: 10,
  minValue: 1,
  maxValue: 1000,
  overridden: false,
  updatedAt: null,
}

function serve(settings: ConnectionLifecycleSettingResponse[], minimumGroupSize = 5) {
  const body: ConnectionLifecycleSettingsResponse = { settings, minimumGroupSize }
  server.use(http.get(PATH, () => HttpResponse.json(body)))
}

function saveCapture() {
  const sent: { setting: string; body: unknown }[] = []
  server.use(
    http.put(`${PATH}/:setting`, async ({ params, request }) => {
      const body = (await request.json()) as { value: number | null }
      const setting = String(params.setting)
      sent.push({ setting, body })
      const base = [inactivity, deletion, warning].find((entry) => entry.setting === setting)!
      return HttpResponse.json({
        ...base,
        value: body.value ?? base.defaultValue,
        overridden: body.value !== null,
      })
    }),
  )
  return sent
}

function row(name: string) {
  return screen.getByRole('row', { name: new RegExp(`^${name}`) })
}

describe('ConnectionLifecycleSection', () => {
  it('names each value in force, its bounds and whether it is the factory default', async () => {
    serve([inactivity, deletion, warning])
    renderWithProviders(<ConnectionLifecycleSection />)

    await screen.findByRole('table', { name: 'Schwellen und Fristen der Verbindungen' })
    expect(screen.getByLabelText('Inaktivitätsschwelle in Tagen')).toHaveValue('90')
    expect(screen.getByLabelText('Löschfrist privater Bibliotheken in Tagen')).toHaveValue('14')
    expect(screen.getByLabelText('Warnschwelle abgelaufener Konten')).toHaveValue('10')
    expect(within(row('Inaktivitätsschwelle')).getByText('Werkseinstellung')).toBeInTheDocument()
    expect(
      within(row('Löschfrist privater Bibliotheken')).getByText(
        'Eigener Wert (Werkseinstellung: 30)',
      ),
    ).toBeInTheDocument()
    expect(within(row('Inaktivitätsschwelle')).getByText('30 bis 365 Tage')).toBeInTheDocument()
  })

  it('sets a value after a confirmation that names its effect', async () => {
    serve([inactivity, deletion, warning])
    const sent = saveCapture()
    const user = userEvent.setup()
    renderWithProviders(<ConnectionLifecycleSection />)

    const field = await screen.findByLabelText('Inaktivitätsschwelle in Tagen')
    await user.clear(field)
    await user.type(field, '120')
    await user.click(screen.getByRole('button', { name: 'Inaktivitätsschwelle speichern' }))

    const question = 'Inaktivitätsschwelle auf 120 ändern?'
    expect(await screen.findByRole('dialog', { name: question })).toHaveTextContent(
      /Revisionsprotokoll/,
    )
    await answerConfirm(user, question, 'Wert ändern')

    await waitFor(() =>
      expect(sent).toEqual([{ setting: 'INACTIVITY_THRESHOLD_DAYS', body: { value: 120 } }]),
    )
    expect(
      await within(row('Inaktivitätsschwelle')).findByText('Eigener Wert (Werkseinstellung: 90)'),
    ).toBeInTheDocument()
  })

  it('returns an own value to the factory default', async () => {
    serve([inactivity, deletion, warning])
    const sent = saveCapture()
    const user = userEvent.setup()
    renderWithProviders(<ConnectionLifecycleSection />)

    await user.click(
      await screen.findByRole('button', {
        name: 'Löschfrist privater Bibliotheken auf die Werkseinstellung zurücksetzen',
      }),
    )
    await answerConfirm(
      user,
      'Löschfrist privater Bibliotheken auf die Werkseinstellung von 30 zurücksetzen?',
      'Werkseinstellung wiederherstellen',
    )

    await waitFor(() =>
      expect(sent).toEqual([{ setting: 'PRIVATE_LIBRARY_DELETION_DAYS', body: { value: null } }]),
    )
  })

  it('warns that private libraries go earlier than before when the period gets shorter', async () => {
    serve([inactivity, { ...deletion, value: 60 }, warning])
    saveCapture()
    const user = userEvent.setup()
    renderWithProviders(<ConnectionLifecycleSection />)

    const field = await screen.findByLabelText('Löschfrist privater Bibliotheken in Tagen')
    await user.clear(field)
    await user.type(field, '7')
    await user.click(
      screen.getByRole('button', { name: 'Löschfrist privater Bibliotheken speichern' }),
    )
    expect(
      await screen.findByRole('dialog', { name: 'Löschfrist privater Bibliotheken auf 7 ändern?' }),
    ).toHaveTextContent(/früher als bisher/)
    await user.click(screen.getByRole('button', { name: 'Abbrechen' }))

    await user.click(
      screen.getByRole('button', {
        name: 'Löschfrist privater Bibliotheken auf die Werkseinstellung zurücksetzen',
      }),
    )
    expect(
      await screen.findByRole('dialog', {
        name: 'Löschfrist privater Bibliotheken auf die Werkseinstellung von 30 zurücksetzen?',
      }),
    ).toHaveTextContent(/früher als bisher/)
  })

  it('does not warn of an earlier erasure when the period gets longer', async () => {
    serve([inactivity, deletion, warning])
    saveCapture()
    const user = userEvent.setup()
    renderWithProviders(<ConnectionLifecycleSection />)

    await user.click(
      await screen.findByRole('button', {
        name: 'Löschfrist privater Bibliotheken auf die Werkseinstellung zurücksetzen',
      }),
    )
    expect(
      await screen.findByRole('dialog', {
        name: 'Löschfrist privater Bibliotheken auf die Werkseinstellung von 30 zurücksetzen?',
      }),
    ).not.toHaveTextContent(/früher als bisher/)
  })

  it('refuses a value outside the bounds before sending', async () => {
    serve([inactivity, deletion, warning])
    const sent = saveCapture()
    const user = userEvent.setup()
    renderWithProviders(<ConnectionLifecycleSection />)

    const field = await screen.findByLabelText('Löschfrist privater Bibliotheken in Tagen')
    await user.clear(field)
    await user.type(field, '91')

    expect(screen.getByText('Bitte eine ganze Zahl von 1 bis 90 eingeben.')).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Löschfrist privater Bibliotheken speichern' }),
    ).toBeDisabled()
    expect(sent).toEqual([])
  })

  it('warns when the warning threshold lies below the minimum group size', async () => {
    serve([inactivity, deletion, { ...warning, value: 3, overridden: true }], 5)
    renderWithProviders(<ConnectionLifecycleSection />)

    expect(await screen.findByText(/unter der Mindestgruppengröße von 5/)).toBeInTheDocument()
  })

  it('offers to load again when loading fails', async () => {
    server.use(
      http.get(PATH, () => HttpResponse.json({ error: 'Kaputt', status: 500 }, { status: 500 })),
    )
    renderWithProviders(<ConnectionLifecycleSection />)

    expect(await screen.findByRole('button', { name: 'Erneut laden' })).toBeInTheDocument()
  })
})
