import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../../mocks/server'
import { answerConfirm, renderWithProviders } from '../../../test/test-utils'
import type { PrivateStorageQuotaResponse, PrivateStorageSummaryResponse } from '../../../types/api'
import PrivateStorageSection from './PrivateStorageSection'

const QUOTA = '/api/v1/admin/private-libraries/quota'
const SUMMARY = '/api/v1/admin/private-libraries/summary'
const GIB = 1024 * 1024 * 1024

function quota(overrides: Partial<PrivateStorageQuotaResponse> = {}) {
  server.use(
    http.get(QUOTA, () =>
      HttpResponse.json({
        quotaBytes: 10 * GIB,
        defaultQuotaBytes: 10 * GIB,
        overridden: false,
        ...overrides,
      }),
    ),
  )
}

function summary(body: PrivateStorageSummaryResponse) {
  server.use(http.get(SUMMARY, () => HttpResponse.json(body)))
}

const exactSummary: PrivateStorageSummaryResponse = {
  quotaBytes: 10 * GIB,
  owners: { value: 12 },
  usedBytes: { value: 3 * GIB },
  profiles: [
    { profileId: 'p-1', name: 'Nextcloud intern', usedBytes: { value: 2 * GIB } },
    { profileId: 'p-2', name: 'Dateiserver', usedBytes: {} },
  ],
  runWindowDays: 30,
  runEnds: [
    { category: 'QUOTA_EXHAUSTED', runs: { value: 7 } },
    { category: 'EXPIRED', runs: { fewerThanPersons: 5 } },
  ],
}

async function saveCapture() {
  const sent: unknown[] = []
  server.use(
    http.put(QUOTA, async ({ request }) => {
      const body = (await request.json()) as { quotaBytes: number | null }
      sent.push(body)
      return HttpResponse.json({
        quotaBytes: body.quotaBytes ?? 10 * GIB,
        defaultQuotaBytes: 10 * GIB,
        overridden: body.quotaBytes !== null,
      })
    }),
  )
  return sent
}

describe('PrivateStorageSection – Grenze (#2276)', () => {
  it('shows the limit in force and that it is the default', async () => {
    quota()
    summary(exactSummary)
    renderWithProviders(<PrivateStorageSection />)

    expect(await screen.findByTestId('private-storage-quota-current')).toHaveTextContent(
      'Es gilt die Vorgabe der Installation: 10 GB je Person.',
    )
    expect(screen.getByLabelText('Grenze je Person (GB)')).toHaveValue(10)
    expect(
      screen.queryByRole('button', { name: 'Vorgabe wiederherstellen' }),
    ).not.toBeInTheDocument()
  })

  it('sets an own limit in bytes after a confirmation naming the effect', async () => {
    quota()
    summary(exactSummary)
    const sent = await saveCapture()
    const user = userEvent.setup()
    renderWithProviders(<PrivateStorageSection />)

    const field = await screen.findByLabelText('Grenze je Person (GB)')
    await user.clear(field)
    await user.type(field, '20')
    await user.click(screen.getByRole('button', { name: 'Grenze speichern' }))

    const question = 'Speicherkontingent privater Bibliotheken auf 20 GB je Person ändern?'
    expect(await screen.findByRole('dialog', { name: question })).toHaveTextContent(
      /ab dem nächsten aufgenommenen Dokument.*Bereits Gespeichertes bleibt/,
    )
    await answerConfirm(user, question, 'Grenze ändern')

    await waitFor(() => expect(sent).toEqual([{ quotaBytes: 20 * GIB }]))
    expect(await screen.findByTestId('private-storage-quota-current')).toHaveTextContent(
      'Es gilt eine eigene Grenze: 20 GB je Person (Vorgabe der Installation: 10 GB).',
    )
  })

  it('sends 0 for „unbegrenzt“', async () => {
    quota()
    summary(exactSummary)
    const sent = await saveCapture()
    const user = userEvent.setup()
    renderWithProviders(<PrivateStorageSection />)

    await user.click(await screen.findByRole('checkbox', { name: 'Unbegrenzt' }))
    expect(screen.getByLabelText('Grenze je Person (GB)')).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Grenze speichern' }))
    await answerConfirm(user, /unbegrenzt/, 'Grenze ändern')

    await waitFor(() => expect(sent).toEqual([{ quotaBytes: 0 }]))
  })

  it('returns to the default with null', async () => {
    quota({ quotaBytes: 20 * GIB, overridden: true })
    summary(exactSummary)
    const sent = await saveCapture()
    const user = userEvent.setup()
    renderWithProviders(<PrivateStorageSection />)

    await user.click(await screen.findByRole('button', { name: 'Vorgabe wiederherstellen' }))
    await answerConfirm(user, /zur Vorgabe von 10 GB zurückkehren\?/, 'Vorgabe wiederherstellen')

    await waitFor(() => expect(sent).toEqual([{ quotaBytes: null }]))
  })

  it('refuses an empty or negative limit before sending anything', async () => {
    quota()
    summary(exactSummary)
    const sent = await saveCapture()
    const user = userEvent.setup()
    renderWithProviders(<PrivateStorageSection />)

    const field = await screen.findByLabelText('Grenze je Person (GB)')
    await user.clear(field)
    await user.type(field, '-1')

    expect(screen.getByText('Bitte eine Zahl größer als 0 eingeben.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Grenze speichern' })).toBeDisabled()
    expect(sent).toEqual([])
  })
})

describe('PrivateStorageSection – Übersicht (#2276)', () => {
  it('shows every number exactly as the server tells it, with no own sums', async () => {
    quota()
    summary({ ...exactSummary, usedBytes: {} })
    renderWithProviders(<PrivateStorageSection />)

    const overview = await screen.findByTestId('private-storage-summary')
    expect(within(overview).getByTestId('private-storage-owners')).toHaveTextContent('12')
    // the total is not told - the frontend never adds the per-profile parts up itself
    expect(within(overview).getByTestId('private-storage-used')).toHaveTextContent(
      'nicht ausgewiesen',
    )
    expect(overview).not.toHaveTextContent('3 GB')
    const profiles = within(overview).getByRole('table', { name: 'Belegter Speicher je Zugang' })
    expect(within(profiles).getByRole('row', { name: /Nextcloud intern/ })).toHaveTextContent(
      '2 GB',
    )
    expect(within(profiles).getByRole('row', { name: /Dateiserver/ })).toHaveTextContent(
      'nicht ausgewiesen',
    )
  })

  it('shows a number resting on few persons only as „weniger als N“', async () => {
    quota()
    summary({
      ...exactSummary,
      owners: { fewerThanPersons: 5 },
      usedBytes: { fewerThanPersons: 5 },
    })
    renderWithProviders(<PrivateStorageSection />)

    expect(await screen.findByTestId('private-storage-owners')).toHaveTextContent('weniger als 5')
    expect(screen.getByTestId('private-storage-used')).toHaveTextContent(
      'nicht ausgewiesen (weniger als 5 Personen)',
    )
  })

  it('names run ends per category over the server’s window', async () => {
    quota()
    summary(exactSummary)
    renderWithProviders(<PrivateStorageSection />)

    const runs = await screen.findByRole('table', {
      name: 'Laufabbrüche der letzten 30 Tage je Ursache',
    })
    expect(
      within(runs).getByRole('row', { name: /Speicherkontingent erschöpft/ }),
    ).toHaveTextContent('7')
    expect(within(runs).getByRole('row', { name: /Anmeldung abgelaufen/ })).toHaveTextContent(
      'weniger als 5',
    )
  })
})
