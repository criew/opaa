import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { answerConfirm, renderWithProviders } from '@/test/test-utils'
import type { OutboundProxyRequest, OutboundProxyResponse } from '@/shared/api/types'
import OutboundProxySection from './OutboundProxySection'

const PATH = '/api/v1/admin/outbound-proxy'

function stored(body: OutboundProxyResponse) {
  server.use(http.get(PATH, () => HttpResponse.json(body)))
}

function saveCapture() {
  const sent: OutboundProxyRequest[] = []
  server.use(
    http.put(PATH, async ({ request }) => {
      const body = (await request.json()) as OutboundProxyRequest
      sent.push(body)
      return HttpResponse.json({
        proxyUrl: body.proxyUrl ? `http://${body.proxyUrl.replace(/^http:\/\//, '')}` : null,
        noProxy: body.noProxy,
        updatedAt: '2026-10-11T08:00:00Z',
      })
    }),
  )
  return sent
}

describe('OutboundProxySection', () => {
  it('says that everything goes direct without a proxy and points to the allowlist', async () => {
    stored({ proxyUrl: null, noProxy: [], updatedAt: null })
    renderWithProviders(<OutboundProxySection />)

    expect(await screen.findByTestId('outbound-proxy-current')).toHaveTextContent(
      'Es ist kein Ausgangsproxy eingetragen; alle Verbindungen gehen direkt hinaus.',
    )
    expect(screen.getByText(/OPAA_INDEXING_TARGET_VALIDATION_ALLOWLIST/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ausgangsproxy speichern' })).toBeDisabled()
  })

  it('shows the stored proxy and one exclusion per line', async () => {
    stored({
      proxyUrl: 'http://proxy.example.org:3128',
      noProxy: ['intranet.example', '10.0.0.0/8'],
      updatedAt: '2026-10-11T08:00:00Z',
    })
    renderWithProviders(<OutboundProxySection />)

    expect(await screen.findByTestId('outbound-proxy-current')).toHaveTextContent(
      'Ausgehende Verbindungen gehen über http://proxy.example.org:3128.',
    )
    expect(screen.getByLabelText('Proxy-Adresse')).toHaveValue('http://proxy.example.org:3128')
    expect(screen.getByLabelText('Ausnahmeliste')).toHaveValue('intranet.example\n10.0.0.0/8')
  })

  it('sends the address and the lines of the list after a confirmation', async () => {
    stored({ proxyUrl: null, noProxy: [], updatedAt: null })
    const sent = saveCapture()
    const user = userEvent.setup()
    renderWithProviders(<OutboundProxySection />)

    await user.click(await screen.findByLabelText('Proxy-Adresse'))
    await user.paste('proxy.example.org:3128')
    await user.click(screen.getByLabelText('Ausnahmeliste'))
    await user.paste('intranet.example\n\n10.0.0.0/8')
    await user.click(screen.getByRole('button', { name: 'Ausgangsproxy speichern' }))

    const question = 'Ausgangsproxy proxy.example.org:3128 speichern?'
    expect(await screen.findByRole('dialog', { name: question })).toHaveTextContent(
      /Anmeldung über den Anbieter lahmlegen/,
    )
    await answerConfirm(user, question, 'Speichern')

    await waitFor(() =>
      expect(sent).toEqual([
        { proxyUrl: 'proxy.example.org:3128', noProxy: ['intranet.example', '10.0.0.0/8'] },
      ]),
    )
    expect(await screen.findByTestId('outbound-proxy-current')).toHaveTextContent(
      'Ausgehende Verbindungen gehen über http://proxy.example.org:3128.',
    )
  })

  it('sends null when the address is cleared', async () => {
    stored({ proxyUrl: 'http://proxy.example.org:3128', noProxy: [], updatedAt: null })
    const sent = saveCapture()
    const user = userEvent.setup()
    renderWithProviders(<OutboundProxySection />)

    await user.clear(await screen.findByLabelText('Proxy-Adresse'))
    await user.click(screen.getByRole('button', { name: 'Ausgangsproxy speichern' }))
    await answerConfirm(
      user,
      'Ausgangsproxy entfernen und alle Verbindungen direkt aufbauen?',
      'Speichern',
    )

    await waitFor(() => expect(sent).toEqual([{ proxyUrl: null, noProxy: [] }]))
  })

  it('shows a rejected entry at its field', async () => {
    stored({ proxyUrl: null, noProxy: [], updatedAt: null })
    server.use(
      http.put(PATH, () =>
        HttpResponse.json(
          {
            error: 'Die Einstellungen des Ausgangsproxys sind ungültig.',
            status: 400,
            fieldErrors: [
              {
                field: 'noProxy',
                code: 'INVALID',
                message: 'Kein gültiger Eintrag der Ausnahmeliste: *.',
              },
            ],
          },
          { status: 400 },
        ),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<OutboundProxySection />)

    await user.type(await screen.findByLabelText('Ausnahmeliste'), '*')
    await user.click(screen.getByRole('button', { name: 'Ausgangsproxy speichern' }))
    await answerConfirm(
      user,
      'Ausgangsproxy entfernen und alle Verbindungen direkt aufbauen?',
      'Speichern',
    )

    expect(
      await screen.findByText('Kein gültiger Eintrag der Ausnahmeliste: *.'),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('Ausnahmeliste')).toHaveAttribute('aria-invalid', 'true')
  })
})
