import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { renderWithProviders } from '@/test/test-utils'
import { resetTextRecognitionMockState } from '../mocks/textRecognitionHandlers'
import TextRecognitionSection from './TextRecognitionSection'

const STORED = {
  enabled: false,
  binding: 'DOCLING_SERVE',
  baseUrl: null,
  model: null,
  apiKeySet: false,
  state: 'DISABLED',
  operationEnabled: true,
  updatedAt: null,
  retiredVariables: [],
}

function stored(patch: Record<string, unknown>) {
  server.use(
    http.get('/api/v1/admin/models/text-recognition', () =>
      HttpResponse.json({ ...STORED, ...patch }),
    ),
  )
}

afterEach(() => resetTextRecognitionMockState())

describe('TextRecognitionSection (UI lab, variant A by default)', () => {
  it('shows only the notice while the operation switch is off', async () => {
    stored({ operationEnabled: false, baseUrl: 'http://opaa-ocr:5001' })
    renderWithProviders(<TextRecognitionSection />)

    expect(await screen.findByText(/nicht freigegeben \(OPAA_OCR_ENABLED\)/)).toBeInTheDocument()
    expect(screen.queryByLabelText('Adresse')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Speichern' })).not.toBeInTheDocument()
  })

  it('shows the empty role switched off with docling-serve preselected', async () => {
    stored({})
    renderWithProviders(<TextRecognitionSection />)

    expect(await screen.findByText('Zustand: Ausgeschaltet.')).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Texterkennung einschalten' })).not.toBeChecked()
    expect(screen.getByRole('radio', { name: /docling-serve/ })).toBeChecked()
    expect(screen.queryByLabelText('Modell-Kennung')).not.toBeInTheDocument()
  })

  it('asks for the model and names the page-only marking with a vision model', async () => {
    const user = userEvent.setup()
    stored({})
    renderWithProviders(<TextRecognitionSection />)

    await user.click(await screen.findByRole('radio', { name: /Vision-Modell/ }))

    expect(screen.getByLabelText('Modell-Kennung')).toBeInTheDocument()
    expect(screen.getByText(/nur auf die Seite der Fundstelle, ohne Rahmen/)).toBeInTheDocument()
  })

  it('shows the address check of a blocked internal target at the field', async () => {
    const user = userEvent.setup()
    stored({ enabled: true, baseUrl: 'http://opaa-ocr:5001', apiKeySet: true, state: 'ACTIVE' })
    renderWithProviders(<TextRecognitionSection />)

    const address = await screen.findByLabelText('Adresse')
    await user.clear(address)
    await user.type(address, 'http://10.20.0.5:5001')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    await waitFor(() =>
      expect(screen.getByText(/interne Ziel ist nicht freigegeben/)).toBeInTheDocument(),
    )
    expect(address).toHaveAttribute('aria-invalid', 'true')
  })

  it('reports a successful connection test of the configured service', async () => {
    const user = userEvent.setup()
    stored({ enabled: true, baseUrl: 'http://opaa-ocr:5001', apiKeySet: true, state: 'ACTIVE' })
    renderWithProviders(<TextRecognitionSection />)

    await user.click(await screen.findByRole('button', { name: 'Verbindung testen' }))

    expect(await screen.findByText(/Testseite erkannt/)).toBeInTheDocument()
  })
})
