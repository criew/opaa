import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
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
  return screen.getByRole('row', { name: new RegExp(`^${name}`) })
}

describe('FullSyncRhythmSection', () => {
  it('names the rhythm in force per source type and whether it is the factory default', async () => {
    list([confluence, sharePoint])
    renderWithProviders(<FullSyncRhythmSection />)

    await screen.findByRole('table', { name: 'Vollabgleich-Rhythmus je Quellart' })
    expect(within(row('Confluence')).getByText('Werkseinstellung')).toBeInTheDocument()
    expect(
      within(row('SharePoint')).getByText('Eigener Wert (Werkseinstellung: 1 Tag)'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('Vollabgleich von Confluence alle … Tage')).toHaveValue('7')
    expect(screen.getByLabelText('Vollabgleich von SharePoint alle … Tage')).toHaveValue('3')
    expect(
      within(row('Confluence')).queryByRole('button', { name: /Werkseinstellung/ }),
    ).not.toBeInTheDocument()
  })

  it('sets the days of one type after a confirmation naming its effect', async () => {
    list([confluence, sharePoint])
    const sent = saveCapture()
    const user = userEvent.setup()
    renderWithProviders(<FullSyncRhythmSection />)

    const field = await screen.findByLabelText('Vollabgleich von Confluence alle … Tage')
    await user.clear(field)
    await user.type(field, '14')
    await user.click(screen.getByRole('button', { name: 'Rhythmus von Confluence speichern' }))

    const question = 'Vollabgleich-Rhythmus von Confluence auf 14 Tage ändern?'
    expect(await screen.findByRole('dialog', { name: question })).toHaveTextContent(
      /ohne eigenen Rhythmus.*Revisionsprotokoll/,
    )
    await answerConfirm(user, question, 'Rhythmus ändern')

    await waitFor(() => expect(sent).toEqual([{ type: 'CONFLUENCE', body: { intervalDays: 14 } }]))
    expect(
      await within(row('Confluence')).findByText('Eigener Wert (Werkseinstellung: 7 Tage)'),
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
      'Werkseinstellung wiederherstellen',
    )

    await waitFor(() =>
      expect(sent).toEqual([{ type: 'SHAREPOINT', body: { intervalDays: null } }]),
    )
    expect(await within(row('SharePoint')).findByText('Werkseinstellung')).toBeInTheDocument()
  })

  it.each(['0', '366', '2,5', 'x'])('refuses %s before sending anything', async (typed) => {
    list([confluence])
    const sent = saveCapture()
    const user = userEvent.setup()
    renderWithProviders(<FullSyncRhythmSection />)

    const field = await screen.findByLabelText('Vollabgleich von Confluence alle … Tage')
    await user.clear(field)
    await user.type(field, typed)

    expect(screen.getByText('Bitte eine ganze Zahl von 1 bis 365 eingeben.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Rhythmus von Confluence speichern' })).toBeDisabled()
    expect(sent).toEqual([])
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
    expect(await screen.findByLabelText('Vollabgleich von Confluence alle … Tage')).toHaveValue('7')
  })
})
