import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import { renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import { useLlmModelStore } from '../stores/llmModelStore'
import LlmModelManagementPage from './LlmModelManagementPage'

function signInAs(systemRole: 'SYSTEM_ADMIN' | 'USER') {
  useAuthStore.setState({
    mode: 'dev',
    isAuthenticated: true,
    isLoading: false,
    user: {
      id: 'user-1',
      email: 'admin@opaa.local',
      displayName: 'Admin',
      systemRole,
    },
    token: null,
    error: null,
    userManager: null,
  })
}

describe('LlmModelManagementPage', () => {
  beforeEach(() => {
    useLlmModelStore.setState({
      models: [],
      embeddingInfo: null,
      isLoading: false,
      error: null,
    })
  })

  /** #759 acceptance criterion: no route/entry for anyone but SYSTEM_ADMIN. */
  it('shows no model management to a user who is not a system administrator', () => {
    signInAs('USER')

    renderWithProviders(<LlmModelManagementPage />)

    expect(screen.queryByRole('button', { name: 'Neues Modell' })).not.toBeInTheDocument()
    expect(screen.getByText(/nicht freigegeben/i)).toBeInTheDocument()
  })

  it('lists the configured models with the active one clearly marked', async () => {
    signInAs('SYSTEM_ADMIN')

    renderWithProviders(<LlmModelManagementPage />)

    await waitFor(() => {
      expect(screen.getByText('Ollama lokal')).toBeInTheDocument()
    })
    expect(screen.getByLabelText('Aktives Modell')).toBeInTheDocument()
  })

  it('shows the read-only embedding block with provider, model and dimensions', async () => {
    signInAs('SYSTEM_ADMIN')

    renderWithProviders(<LlmModelManagementPage />)

    await waitFor(() => {
      expect(screen.getByText('nomic-embed-text')).toBeInTheDocument()
    })
    expect(screen.getByText('ollama')).toBeInTheDocument()
    expect(screen.getByText('1536')).toBeInTheDocument()
    expect(screen.getByText(/vollständige Neuindizierung/i)).toBeInTheDocument()
  })

  it('creates a model without an API key without a validation error', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()

    renderWithProviders(<LlmModelManagementPage />)
    await user.click(screen.getByRole('button', { name: 'Neues Modell' }))
    const dialog = within(screen.getByRole('dialog'))

    await user.type(dialog.getByLabelText('Anzeigename'), 'Neues Modell')
    await user.type(dialog.getByLabelText('Basis-Adresse'), 'http://localhost:11434/v1')
    await user.type(dialog.getByLabelText('Modell-Kennung'), 'llama3')
    await user.click(dialog.getByRole('button', { name: 'Anlegen' }))

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    })
    expect(useLlmModelStore.getState().models.some((m) => m.displayName === 'Neues Modell')).toBe(
      true,
    )
  })

  it('activates a model, with a visible effect in the list', async () => {
    signInAs('SYSTEM_ADMIN')
    // Sets up a second, inactive model against the real mock handlers (mockLlmModels), so
    // activation below exercises the whole path - store action, POST .../activate, reload - not
    // just a hand-crafted server response.
    await useLlmModelStore.getState().createNewModel({
      displayName: 'Modell B',
      baseUrl: 'http://b/v1',
      modelIdentifier: 'b',
      temperature: 0.7,
      maxTokens: 2000,
    })
    const user = userEvent.setup()

    renderWithProviders(<LlmModelManagementPage />)
    await waitFor(() => screen.getByText('Modell B'))
    await user.click(screen.getByText('Modell B'))
    await user.click(screen.getByRole('button', { name: '"Modell B" als aktives Modell setzen' }))

    await waitFor(() => {
      expect(
        useLlmModelStore.getState().models.find((m) => m.displayName === 'Modell B')?.active,
      ).toBe(true)
    })
  })

  it('runs a connection test and shows the outcome', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()

    renderWithProviders(<LlmModelManagementPage />)
    await waitFor(() => screen.getByText('Ollama lokal'))
    await user.click(screen.getByText('Ollama lokal'))
    await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))

    await waitFor(() => {
      expect(screen.getByText(/Verbindung erfolgreich/i)).toBeInTheDocument()
    })
  })

  it('shows the API failure message on a failed connection test, form stays editable', async () => {
    signInAs('SYSTEM_ADMIN')
    server.use(
      http.post('/api/v1/admin/models/test', () =>
        HttpResponse.json({ success: false, message: 'Modell nicht erreichbar' }),
      ),
    )
    const user = userEvent.setup()

    renderWithProviders(<LlmModelManagementPage />)
    await waitFor(() => screen.getByText('Ollama lokal'))
    await user.click(screen.getByText('Ollama lokal'))
    await user.click(screen.getByRole('button', { name: 'Verbindung testen' }))

    await waitFor(() => {
      expect(screen.getByText('Modell nicht erreichbar')).toBeInTheDocument()
    })
    expect(screen.getByLabelText('Anzeigename')).toBeEnabled()
  })

  it('rejects deleting the active model client-side, disabling the delete button', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()

    renderWithProviders(<LlmModelManagementPage />)
    await waitFor(() => screen.getByText('Ollama lokal'))
    await user.click(screen.getByText('Ollama lokal'))

    const deleteButton = screen.getByRole('button', { name: 'Modell löschen' })
    expect(deleteButton).toBeDisabled()
  })

  it('shows the API 409 message when a delete is rejected server-side', async () => {
    signInAs('SYSTEM_ADMIN')
    // An inactive model whose delete button is enabled client-side, but the server rejects anyway
    // (e.g. a concurrent activation just before this request landed) - the 409 message must still
    // reach the user rather than being swallowed.
    server.use(
      http.get('/api/v1/admin/models', () =>
        HttpResponse.json([
          {
            id: 'model-a',
            displayName: 'Modell A',
            baseUrl: 'http://a/v1',
            modelIdentifier: 'a',
            temperature: 0.7,
            maxTokens: 2000,
            apiKeySet: false,
            active: false,
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          },
        ]),
      ),
      http.delete('/api/v1/admin/models/model-a', () =>
        HttpResponse.json(
          { error: 'Das aktive Chat-Modell kann nicht gelöscht werden.' },
          { status: 409 },
        ),
      ),
    )
    const user = userEvent.setup()
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)

    renderWithProviders(<LlmModelManagementPage />)
    await waitFor(() => screen.getByText('Modell A'))
    await user.click(screen.getByText('Modell A'))
    await user.click(screen.getByRole('button', { name: 'Modell löschen' }))

    await waitFor(() => {
      expect(screen.getByText(/nicht gelöscht werden/i)).toBeInTheDocument()
    })
    confirmSpy.mockRestore()
  })
})
