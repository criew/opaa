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
  pausedBy: [],
}
const deletion: ConnectionLifecycleSettingResponse = {
  setting: 'PRIVATE_LIBRARY_DELETION_DAYS',
  value: 14,
  defaultValue: 30,
  minValue: 1,
  maxValue: 90,
  overridden: true,
  updatedAt: '2026-10-20T08:00:00Z',
  pausedBy: [],
}
const warning: ConnectionLifecycleSettingResponse = {
  setting: 'EXPIRED_CONNECTION_WARNING_THRESHOLD',
  value: 10,
  defaultValue: 10,
  minValue: 1,
  maxValue: 1000,
  overridden: false,
  updatedAt: null,
  pausedBy: [],
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
  return screen.getByRole('region', { name })
}

/** Each setting is a value row: „Ändern“ opens its field. */
async function openField(user: ReturnType<typeof userEvent.setup>, name: string, label: string) {
  await user.click(await screen.findByRole('button', { name: `${name} ändern` }))
  return screen.getByLabelText(label)
}

describe('ConnectionLifecycleSection', () => {
  it('names each value in force, its bounds and whether it is the factory default', async () => {
    serve([inactivity, deletion, warning])
    const user = userEvent.setup()
    renderWithProviders(<ConnectionLifecycleSection />)

    await screen.findByRole('group', { name: 'Schwellen und Fristen der Verbindungen' })
    expect(
      within(row('Inaktivitätsschwelle')).getByText('90 Tage · Werkseinstellung'),
    ).toBeInTheDocument()
    expect(
      within(row('Löschfrist privater Bibliotheken')).getByText(
        '14 Tage · eigener Wert (Werkseinstellung: 30)',
      ),
    ).toBeInTheDocument()
    expect(
      within(row('Warnschwelle abgelaufener Konten')).getByText('10 · Werkseinstellung'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Speichern' })).not.toBeInTheDocument()
    expect(
      await openField(user, 'Inaktivitätsschwelle', 'Inaktivitätsschwelle in Tagen'),
    ).toHaveValue('90')
    expect(within(row('Inaktivitätsschwelle')).getByText('30 bis 365 Tage')).toBeInTheDocument()
  })

  it('sets a value after a confirmation that names its effect', async () => {
    serve([inactivity, deletion, warning])
    const sent = saveCapture()
    const user = userEvent.setup()
    renderWithProviders(<ConnectionLifecycleSection />)

    const field = await openField(user, 'Inaktivitätsschwelle', 'Inaktivitätsschwelle in Tagen')
    await user.clear(field)
    await user.type(field, '120')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    const question = 'Inaktivitätsschwelle auf 120 ändern?'
    expect(await screen.findByRole('dialog', { name: question })).toHaveTextContent(
      /Revisionsprotokoll/,
    )
    await answerConfirm(user, question, 'Ändern')

    await waitFor(() =>
      expect(sent).toEqual([{ setting: 'INACTIVITY_THRESHOLD_DAYS', body: { value: 120 } }]),
    )
    expect(
      await within(row('Inaktivitätsschwelle')).findByText(/eigener Wert \(Werkseinstellung: 90\)/),
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
      'Zurücksetzen',
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

    const field = await openField(
      user,
      'Löschfrist privater Bibliotheken',
      'Löschfrist privater Bibliotheken in Tagen',
    )
    await user.clear(field)
    await user.type(field, '7')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))
    expect(
      await screen.findByRole('dialog', { name: 'Löschfrist privater Bibliotheken auf 7 ändern?' }),
    ).toHaveTextContent(/früher als bisher/)
    await user.click(screen.getByRole('button', { name: 'Abbrechen' }))
    // the declined question leaves the row open; the way back to the default stands in the closed row
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
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

  const paused: ConnectionLifecycleSettingResponse = {
    ...deletion,
    value: 30,
    overridden: false,
    pausedBy: [
      {
        variable: 'OPAA_CONNECTION_PRIVATE_LIBRARY_DELETION_DAYS',
        retiredValue: '90',
        effectiveValue: '30',
      },
    ],
  }
  const ENDS_PAUSE =
    /der nächste tägliche Lauf löscht private Bibliotheken, deren Frist danach abgelaufen ist/

  it('ends the pause by storing the shown value only after a confirmation', async () => {
    serve([inactivity, paused, warning])
    const sent = saveCapture()
    const user = userEvent.setup()
    renderWithProviders(<ConnectionLifecycleSection />)

    expect(await screen.findByText('Löschen pausiert')).toBeInTheDocument()
    expect(screen.getByText('OPAA_CONNECTION_PRIVATE_LIBRARY_DELETION_DAYS')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Angezeigten Wert festlegen' }))

    const question = 'Löschfrist privater Bibliotheken auf 30 festlegen?'
    expect(await screen.findByRole('dialog', { name: question })).toHaveTextContent(ENDS_PAUSE)
    await user.click(screen.getByRole('button', { name: 'Abbrechen' }))
    expect(sent).toEqual([])

    await user.click(screen.getByRole('button', { name: 'Angezeigten Wert festlegen' }))
    await answerConfirm(user, question, 'Festlegen')
    await waitFor(() =>
      expect(sent).toEqual([{ setting: 'PRIVATE_LIBRARY_DELETION_DAYS', body: { value: 30 } }]),
    )
  })

  it('warns that saving any value during a pause ends it, a longer one included', async () => {
    serve([inactivity, paused, warning])
    const sent = saveCapture()
    const user = userEvent.setup()
    renderWithProviders(<ConnectionLifecycleSection />)

    const field = await openField(
      user,
      'Löschfrist privater Bibliotheken',
      'Löschfrist privater Bibliotheken in Tagen',
    )
    await user.clear(field)
    await user.type(field, '60')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    expect(
      await screen.findByRole('dialog', {
        name: 'Löschfrist privater Bibliotheken auf 60 ändern?',
      }),
    ).toHaveTextContent(ENDS_PAUSE)
    expect(sent).toEqual([])
  })

  it('refuses a value outside the bounds before sending', async () => {
    serve([inactivity, deletion, warning])
    const sent = saveCapture()
    const user = userEvent.setup()
    renderWithProviders(<ConnectionLifecycleSection />)

    const field = await openField(
      user,
      'Löschfrist privater Bibliotheken',
      'Löschfrist privater Bibliotheken in Tagen',
    )
    await user.clear(field)
    await user.type(field, '91')

    expect(screen.getByText('Bitte eine ganze Zahl von 1 bis 90 eingeben.')).toBeInTheDocument()
    const save = screen.getByRole('button', { name: 'Speichern' })
    expect(save).toBeEnabled()
    await user.click(save)
    expect(sent).toEqual([])
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(field).toBeInvalid()
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
