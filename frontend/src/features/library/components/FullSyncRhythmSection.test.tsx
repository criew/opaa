import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { useNotificationStore } from '@/shared/stores/notificationStore'
import { server } from '@/test/server'
import { answerConfirm, renderWithProviders } from '@/test/test-utils'
import type { FullSyncRhythmResponse } from '@/shared/api/types'
import FullSyncRhythmSection from './FullSyncRhythmSection'

const LIST = '/api/v1/admin/full-sync-rhythms'

const confluence: FullSyncRhythmResponse = {
  sourceType: 'CONFLUENCE',
  displayName: 'Confluence',
  intervalDays: 7,
  defaultIntervalDays: 7,
  overridden: false,
}
const sharePoint: FullSyncRhythmResponse = {
  sourceType: 'SHAREPOINT',
  displayName: 'SharePoint',
  intervalDays: 3,
  defaultIntervalDays: 1,
  overridden: true,
}

function list(rhythms: FullSyncRhythmResponse[]) {
  server.use(http.get(LIST, () => HttpResponse.json(rhythms)))
}

function saveCapture() {
  const sent: { type: string; body: unknown }[] = []
  server.use(
    http.put(`${LIST}/:sourceType`, async ({ params, request }) => {
      const body = (await request.json()) as { intervalDays: number | null }
      const type = String(params.sourceType)
      sent.push({ type, body })
      const base = type === 'SHAREPOINT' ? sharePoint : confluence
      return HttpResponse.json({
        ...base,
        intervalDays: body.intervalDays ?? base.defaultIntervalDays,
        overridden: body.intervalDays !== null,
      })
    }),
  )
  return sent
}

function row(name: string) {
  return screen.getByRole('region', { name })
}

/** Each source type is a value row: „Ändern“ opens its field. */
async function openField(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.click(await screen.findByRole('button', { name: `${name} ändern` }))
  return screen.getByLabelText(`Vollabgleich von ${name} alle … Tage`)
}

describe('FullSyncRhythmSection', () => {
  it('names the rhythm in force per source type and whether it is the factory default', async () => {
    list([confluence, sharePoint])
    const user = userEvent.setup()
    renderWithProviders(<FullSyncRhythmSection />)

    await screen.findByRole('group', { name: 'Vollabgleich-Rhythmus je Quellart' })
    expect(within(row('Confluence')).getByText('7 Tage · Werkseinstellung')).toBeInTheDocument()
    expect(
      within(row('SharePoint')).getByText('3 Tage · eigener Wert (Werkseinstellung: 1 Tag)'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Speichern' })).not.toBeInTheDocument()
    expect(await openField(user, 'SharePoint')).toHaveValue('3')
    expect(
      within(row('Confluence')).queryByRole('button', { name: /Werkseinstellung/ }),
    ).not.toBeInTheDocument()
  })

  it('sets the days of one type after a confirmation naming its effect', async () => {
    list([confluence, sharePoint])
    const sent = saveCapture()
    const user = userEvent.setup()
    renderWithProviders(<FullSyncRhythmSection />)

    const field = await openField(user, 'Confluence')
    await user.clear(field)
    await user.type(field, '14')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    const question = 'Vollabgleich-Rhythmus von Confluence auf 14 Tage ändern?'
    expect(await screen.findByRole('dialog', { name: question })).toHaveTextContent(
      /ohne eigenen Rhythmus.*Revisionsprotokoll/,
    )
    await answerConfirm(user, question, 'Ändern')

    await waitFor(() => expect(sent).toEqual([{ type: 'CONFLUENCE', body: { intervalDays: 14 } }]))
    expect(
      await within(row('Confluence')).findByText(
        '14 Tage · eigener Wert (Werkseinstellung: 7 Tage)',
      ),
    ).toBeInTheDocument()
  })

  it('returns one type to its factory default', async () => {
    list([confluence, sharePoint])
    const sent = saveCapture()
    const user = userEvent.setup()
    renderWithProviders(<FullSyncRhythmSection />)

    await user.click(
      await screen.findByRole('button', {
        name: 'Rhythmus von SharePoint auf die Werkseinstellung zurücksetzen',
      }),
    )
    await answerConfirm(
      user,
      'Vollabgleich-Rhythmus von SharePoint auf die Werkseinstellung von 1 Tag zurücksetzen?',
      'Zurücksetzen',
    )

    await waitFor(() =>
      expect(sent).toEqual([{ type: 'SHAREPOINT', body: { intervalDays: null } }]),
    )
    expect(
      await within(row('SharePoint')).findByText('1 Tag · Werkseinstellung'),
    ).toBeInTheDocument()
  })

  it.each(['0', '366', '2,5', 'x'])('refuses %s before sending anything', async (typed) => {
    list([confluence])
    const sent = saveCapture()
    const user = userEvent.setup()
    renderWithProviders(<FullSyncRhythmSection />)

    const field = await openField(user, 'Confluence')
    await user.clear(field)
    await user.type(field, typed)

    expect(screen.getByText('Bitte eine ganze Zahl von 1 bis 365 eingeben.')).toBeInTheDocument()
    const save = screen.getByRole('button', { name: 'Speichern' })
    expect(save).toBeEnabled()
    await user.click(save)
    expect(sent).toEqual([])
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(field).toBeInvalid()
  })

  it('tells a refused save at the field of its own row and keeps it there', async () => {
    list([confluence, sharePoint])
    server.use(
      http.put(`${LIST}/CONFLUENCE`, () =>
        HttpResponse.json(
          { error: 'Der Vollabgleich-Rhythmus muss zwischen 1 und 365 Tagen liegen', status: 400 },
          { status: 400 },
        ),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<FullSyncRhythmSection />)

    const field = await openField(user, 'Confluence')
    await user.clear(field)
    await user.type(field, '14')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))
    await answerConfirm(user, 'Vollabgleich-Rhythmus von Confluence auf 14 Tage ändern?', 'Ändern')

    expect(
      await within(row('Confluence')).findByText(
        'Der Vollabgleich-Rhythmus muss zwischen 1 und 365 Tagen liegen',
      ),
    ).toBeInTheDocument()
    const other = await openField(user, 'SharePoint')
    await user.clear(other)
    await user.type(other, '4')
    expect(
      within(row('Confluence')).getByText(
        'Der Vollabgleich-Rhythmus muss zwischen 1 und 365 Tagen liegen',
      ),
    ).toBeInTheDocument()
    expect(field).toHaveAttribute('aria-invalid', 'true')
    expect(useNotificationStore.getState().queue).toContainEqual(
      expect.objectContaining({
        severity: 'error',
        message:
          'Rhythmus von Confluence nicht gespeichert: Der Vollabgleich-Rhythmus muss zwischen 1 und 365 Tagen liegen',
      }),
    )
  })

  it('offers a retry when the rhythms cannot be loaded', async () => {
    let calls = 0
    server.use(
      http.get(LIST, () => {
        calls += 1
        return calls === 1
          ? HttpResponse.json({ error: 'kaputt', status: 500 }, { status: 500 })
          : HttpResponse.json([confluence])
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<FullSyncRhythmSection />)

    await user.click(await screen.findByRole('button', { name: 'Erneut laden' }))
    expect(await openField(user, 'Confluence')).toHaveValue('7')
  })
})
