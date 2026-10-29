import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { answerConfirm, renderWithProviders } from '@/test/test-utils'
import type { SharedLibraryQuotaResponse } from '@/shared/api/types'
import SharedLibraryQuotaSection from './SharedLibraryQuotaSection'

const QUOTA = '/api/v1/admin/shared-libraries/quota'
const GIB = 1024 * 1024 * 1024

function quota(overrides: Partial<SharedLibraryQuotaResponse> = {}) {
  server.use(
    http.get(QUOTA, () =>
      HttpResponse.json<SharedLibraryQuotaResponse>({
        quotaBytes: 10 * GIB,
        ownQuotaBytes: null,
        capBytes: 10 * GIB,
        librariesOverQuota: 0,
        ...overrides,
      }),
    ),
  )
}

function saveCapture(librariesOverQuota = 0) {
  const sent: unknown[] = []
  server.use(
    http.put(QUOTA, async ({ request }) => {
      const body = (await request.json()) as { quotaBytes: number | null }
      sent.push(body)
      return HttpResponse.json<SharedLibraryQuotaResponse>({
        quotaBytes: body.quotaBytes ?? 10 * GIB,
        ownQuotaBytes: body.quotaBytes,
        capBytes: 10 * GIB,
        librariesOverQuota,
      })
    }),
  )
  return sent
}

describe('SharedLibraryQuotaSection', () => {
  it('shows the upper limit of the operation as the limit in force without an own value', async () => {
    quota()
    renderWithProviders(<SharedLibraryQuotaSection />)

    expect(await screen.findByTestId('shared-library-quota-current')).toHaveTextContent(
      'Es gilt die Obergrenze des Betriebs: 10 GB je Bibliothek.',
    )
    expect(screen.getByLabelText('Grenze je Bibliothek (GB)')).toHaveValue('10')
    expect(screen.getByText('Höchstens 10 GB, die Obergrenze des Betriebs.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Grenze speichern' })).toBeDisabled()
    expect(
      screen.queryByRole('button', { name: 'Eigene Grenze entfernen' }),
    ).not.toBeInTheDocument()
  })

  it('sets an own limit after a confirmation that nothing is deleted', async () => {
    quota()
    const sent = saveCapture(2)
    const user = userEvent.setup()
    renderWithProviders(<SharedLibraryQuotaSection />)

    const field = await screen.findByLabelText('Grenze je Bibliothek (GB)')
    await user.clear(field)
    await user.type(field, '2,5')
    await user.click(screen.getByRole('button', { name: 'Grenze speichern' }))

    const question = 'Speicherkontingent geteilter Bibliotheken auf 2,5 GB je Bibliothek ändern?'
    expect(await screen.findByRole('dialog', { name: question })).toHaveTextContent(
      /Gelöscht wird nichts.*nimmt nichts mehr auf/,
    )
    await answerConfirm(user, question, 'Grenze ändern')

    await waitFor(() => expect(sent).toEqual([{ quotaBytes: 2.5 * GIB }]))
    expect(await screen.findByTestId('shared-library-quota-current')).toHaveTextContent(
      'Es gilt eine eigene Grenze: 2,5 GB je Bibliothek (Obergrenze des Betriebs: 10 GB).',
    )
    expect(screen.getByTestId('shared-library-quota-over')).toHaveTextContent(
      '2 geteilte Bibliotheken belegen schon mehr als 2,5 GB. Sie behalten alle Dokumente und nehmen erst wieder etwas auf, wenn sie darunter liegen.',
    )
  })

  it('refuses a limit above the upper limit of the operation before sending it', async () => {
    quota()
    const sent = saveCapture()
    const user = userEvent.setup()
    renderWithProviders(<SharedLibraryQuotaSection />)

    const field = await screen.findByLabelText('Grenze je Bibliothek (GB)')
    await user.clear(field)
    await user.type(field, '11')

    expect(
      screen.getByText(
        'Die Grenze darf die Obergrenze des Betriebs von 10 GB nicht überschreiten.',
      ),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Grenze speichern' })).toBeDisabled()
    expect(sent).toEqual([])
  })

  it('removes the own limit, so that the upper limit applies alone', async () => {
    quota({ quotaBytes: 2 * GIB, ownQuotaBytes: 2 * GIB })
    const sent = saveCapture()
    const user = userEvent.setup()
    renderWithProviders(<SharedLibraryQuotaSection />)

    await user.click(await screen.findByRole('button', { name: 'Eigene Grenze entfernen' }))
    const question = 'Eigene Grenze für geteilte Bibliotheken entfernen?'
    expect(await screen.findByRole('dialog', { name: question })).toHaveTextContent(
      'Es gilt dann die Obergrenze des Betriebs von 10 GB.',
    )
    await answerConfirm(user, question, 'Grenze entfernen')

    await waitFor(() => expect(sent).toEqual([{ quotaBytes: null }]))
    expect(await screen.findByTestId('shared-library-quota-current')).toHaveTextContent(
      'Es gilt die Obergrenze des Betriebs: 10 GB je Bibliothek.',
    )
  })

  it('says when the operation lowered its upper limit below the own value', async () => {
    quota({ quotaBytes: 5 * GIB, ownQuotaBytes: 8 * GIB, capBytes: 5 * GIB })
    renderWithProviders(<SharedLibraryQuotaSection />)

    expect(await screen.findByTestId('shared-library-quota-current')).toHaveTextContent(
      'Die eigene Grenze von 8 GB liegt über der Obergrenze des Betriebs; es gilt 5 GB je Bibliothek.',
    )
  })

  it('names no maximum when the operation set no upper limit', async () => {
    quota({ quotaBytes: 0, ownQuotaBytes: null, capBytes: 0 })
    renderWithProviders(<SharedLibraryQuotaSection />)

    expect(await screen.findByTestId('shared-library-quota-current')).toHaveTextContent(
      'Es gilt keine Grenze je Bibliothek.',
    )
    expect(screen.getByLabelText('Grenze je Bibliothek (GB)')).toHaveValue('')
    expect(screen.getByText('Der Betrieb hat keine Obergrenze gesetzt.')).toBeInTheDocument()
  })

  it('shows the refusal of the server at the form', async () => {
    quota()
    server.use(
      http.put(QUOTA, () =>
        HttpResponse.json(
          {
            error: 'Das Speicherkontingent darf die Obergrenze des Betriebs nicht überschreiten.',
            status: 400,
          },
          { status: 400 },
        ),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<SharedLibraryQuotaSection />)

    const field = await screen.findByLabelText('Grenze je Bibliothek (GB)')
    await user.clear(field)
    await user.type(field, '3')
    await user.click(screen.getByRole('button', { name: 'Grenze speichern' }))
    await answerConfirm(
      user,
      'Speicherkontingent geteilter Bibliotheken auf 3 GB je Bibliothek ändern?',
      'Grenze ändern',
    )

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Das Speicherkontingent darf die Obergrenze des Betriebs nicht überschreiten.',
    )
  })
})
