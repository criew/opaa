import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { renderWithProviders } from '@/test/test-utils'
import { configuredTextRecognitionModel, mockModelRoles } from '../mocks/modelRoleFixtures'
import TextRecognitionSection from './TextRecognitionSection'

const PATH = '/api/v1/admin/models/text-recognition'

/** Records each request body and lets the mock handlers of the topic answer it. */
function capture(method: 'put' | 'post', path: string) {
  const sent: Record<string, unknown>[] = []
  server.use(
    http[method](path, async ({ request }) => {
      sent.push((await request.clone().json()) as Record<string, unknown>)
      return undefined
    }),
  )
  return sent
}

/** The list item of one stage of the connection test, by its visible label. */
function stage(label: string) {
  const result = screen.getByRole('region', { name: 'Ergebnis des Verbindungstests' })
  return within(result)
    .getAllByRole('listitem')
    .find((item) => item.textContent?.startsWith(`${label}:`)) as HTMLElement
}

describe('TextRecognitionSection', () => {
  it('shows only the notice while the operation has not released text recognition', async () => {
    mockModelRoles.textRecognition = {
      ...configuredTextRecognitionModel(),
      enabled: false,
      state: 'DISABLED',
      textRecognitionAvailable: false,
    }
    renderWithProviders(<TextRecognitionSection />)

    expect(
      await screen.findByText(/Der Betrieb hat die Texterkennung nicht freigegeben/),
    ).toHaveTextContent('Die gespeicherte Anbindung bleibt erhalten.')
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Speichern' })).not.toBeInTheDocument()
  })

  it('shows the factory default as switched off with docling-serve and no model field', async () => {
    renderWithProviders(<TextRecognitionSection />)

    expect(await screen.findByText('Zustand: Ausgeschaltet.')).toBeInTheDocument()
    const toggle = screen.getByRole('switch', { name: 'Texterkennung einschalten' })
    expect(toggle).not.toBeChecked()
    expect(toggle).toHaveAccessibleDescription(
      'Bestehende Scans werden nach dem Einschalten automatisch nachverarbeitet.',
    )
    expect(screen.getByRole('radio', { name: /docling-serve/ })).toBeChecked()
    expect(screen.getByLabelText(/Basis-Adresse/)).toHaveValue('')
    expect(screen.queryByLabelText(/Modell-Kennung/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Gespeicherten Schlüssel entfernen' })).toBeNull()
    expect(screen.getByRole('button', { name: 'Verbindung testen' })).toBeDisabled()
  })

  it('shows a configured role without ever showing the stored key', async () => {
    mockModelRoles.textRecognition = configuredTextRecognitionModel()
    renderWithProviders(<TextRecognitionSection />)

    expect(
      await screen.findByText('Zustand: Eingeschaltet, der Dienst antwortet.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Texterkennung einschalten' })).toBeChecked()
    expect(screen.getByLabelText(/Basis-Adresse/)).toHaveValue('http://opaa-ocr:5001')
    expect(screen.getByLabelText(/Zugangsschlüssel/)).toHaveValue('')
    expect(
      screen.getByText('Leer lassen, um den gespeicherten Schlüssel unverändert zu lassen.'),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: 'Gespeicherten Schlüssel entfernen' }),
    ).toBeInTheDocument()
  })

  it('saves the docling-serve connection without a model', async () => {
    const user = userEvent.setup()
    const sent = capture('put', PATH)
    renderWithProviders(<TextRecognitionSection />)

    await user.click(await screen.findByRole('switch', { name: 'Texterkennung einschalten' }))
    await user.type(screen.getByLabelText(/Basis-Adresse/), ' http://opaa-ocr:5001 ')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0]).toEqual({
      enabled: true,
      connectionType: 'DOCLING_SERVE',
      baseUrl: 'http://opaa-ocr:5001',
      model: null,
    })
    expect(await screen.findByText('Die Texterkennung wurde gespeichert.')).toBeInTheDocument()
  })

  it('asks for the model of a vision model and names that hits are not framed', async () => {
    const user = userEvent.setup()
    const sent = capture('put', PATH)
    renderWithProviders(<TextRecognitionSection />)

    await user.click(await screen.findByRole('radio', { name: /Vision-Modell/ }))
    expect(screen.getByText(/nicht als Rahmen markiert/)).toBeInTheDocument()
    await user.click(screen.getByRole('switch', { name: 'Texterkennung einschalten' }))
    await user.type(screen.getByLabelText(/Basis-Adresse/), 'http://vllm:8000/v1')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    expect(
      await screen.findAllByText('Die Modell-Kennung fehlt; ein Vision-Modell braucht sie.'),
    ).not.toHaveLength(0)

    await user.type(screen.getByLabelText(/Modell-Kennung/), 'PaddlePaddle/PaddleOCR-VL')
    await user.click(screen.getByRole('button', { name: 'Speichern' }))

    await waitFor(() => expect(sent).toHaveLength(2))
    expect(sent[1]).toEqual({
      enabled: true,
      connectionType: 'OPENAI_VISION',
      baseUrl: 'http://vllm:8000/v1',
      model: 'PaddlePaddle/PaddleOCR-VL',
    })
  })

  it('shows every stage as passed after a successful test, with the stored key kept', async () => {
    const user = userEvent.setup()
    mockModelRoles.textRecognition = configuredTextRecognitionModel()
    const sent = capture('post', `${PATH}/test`)
    renderWithProviders(<TextRecognitionSection />)

    await user.click(await screen.findByRole('button', { name: 'Verbindung testen' }))

    expect(
      await screen.findByText('Verbindung erfolgreich, der Dienst hat die Testseite gelesen.'),
    ).toBeInTheDocument()
    expect(stage('Adresse')).toHaveTextContent('Adresse: in Ordnung')
    expect(stage('Anmeldung')).toHaveTextContent('Anmeldung: in Ordnung')
    expect(stage('Erkennung')).toHaveTextContent('Erkennung: in Ordnung')
    expect(sent[0]).toEqual({
      connectionType: 'DOCLING_SERVE',
      baseUrl: 'http://opaa-ocr:5001',
      model: null,
    })
  })

  it('shows a rejected key at the stage „Anmeldung" and leaves recognition unchecked', async () => {
    const user = userEvent.setup()
    mockModelRoles.textRecognition = configuredTextRecognitionModel()
    renderWithProviders(<TextRecognitionSection />)

    await user.type(await screen.findByLabelText(/Zugangsschlüssel/), 'falsch')
    await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))

    await screen.findByRole('region', { name: 'Ergebnis des Verbindungstests' })
    expect(stage('Adresse')).toHaveTextContent('Adresse: in Ordnung')
    expect(stage('Anmeldung')).toHaveTextContent(
      'Anmeldung: fehlgeschlagenDer Dienst hat den Zugangsschlüssel abgelehnt',
    )
    expect(stage('Erkennung')).toHaveTextContent('Erkennung: nicht geprüft')
    expect(screen.queryByText(/falsch/)).not.toBeInTheDocument()
  })

  it('shows a missing engine at the stage „Erkennung"', async () => {
    const user = userEvent.setup()
    renderWithProviders(<TextRecognitionSection />)

    await user.type(await screen.findByLabelText(/Basis-Adresse/), 'http://ohne-engine:5001')
    await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))

    await screen.findByRole('region', { name: 'Ergebnis des Verbindungstests' })
    expect(stage('Anmeldung')).toHaveTextContent('Anmeldung: in Ordnung')
    expect(stage('Erkennung')).toHaveTextContent(/Erkennung: fehlgeschlagen.*RapidOCR/)
  })

  it('shows a refused address at the field and at the stage „Adresse"', async () => {
    const user = userEvent.setup()
    renderWithProviders(<TextRecognitionSection />)

    const address = await screen.findByLabelText(/Basis-Adresse/)
    await user.type(address, 'http://gesperrt:5001')
    await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))

    await screen.findByRole('region', { name: 'Ergebnis des Verbindungstests' })
    expect(stage('Adresse')).toHaveTextContent(/Adresse: fehlgeschlagen.*gesperrten Netzbereich/)
    expect(stage('Anmeldung')).toHaveTextContent('Anmeldung: nicht geprüft')
    expect(address).toHaveAccessibleDescription(
      'Die Adresse liegt in einem gesperrten Netzbereich.',
    )
  })

  it('drops the test result as soon as a connection field changes', async () => {
    const user = userEvent.setup()
    renderWithProviders(<TextRecognitionSection />)

    const address = await screen.findByLabelText(/Basis-Adresse/)
    await user.type(address, 'http://unerreichbar:5001')
    await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))
    expect(await screen.findByText('Die Verbindung wurde vom Server abgelehnt.')).toBeVisible()

    await user.type(address, '1')

    expect(
      screen.queryByRole('region', { name: 'Ergebnis des Verbindungstests' }),
    ).not.toBeInTheDocument()
  })

  it('removes the stored key only through the remove button', async () => {
    const user = userEvent.setup()
    mockModelRoles.textRecognition = configuredTextRecognitionModel()
    const sent = capture('put', PATH)
    renderWithProviders(<TextRecognitionSection />)

    await user.click(await screen.findByRole('button', { name: 'Speichern' }))
    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0]).not.toHaveProperty('apiKey')

    await user.click(screen.getByRole('button', { name: 'Gespeicherten Schlüssel entfernen' }))
    await user.click(screen.getByRole('button', { name: 'Speichern' }))
    await waitFor(() => expect(sent).toHaveLength(2))
    expect(sent[1]).toMatchObject({ apiKey: '' })
  })

  it('keeps a factory role switched off when saved untouched', async () => {
    const user = userEvent.setup()
    const sent = capture('put', PATH)
    renderWithProviders(<TextRecognitionSection />)

    await user.click(await screen.findByRole('button', { name: 'Speichern' }))

    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0]).toMatchObject({ enabled: false, baseUrl: '' })
  })
})
