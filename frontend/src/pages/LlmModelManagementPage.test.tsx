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
    expect(screen.getByText('openai')).toBeInTheDocument()
    expect(screen.getByText('1536')).toBeInTheDocument()
    expect(screen.getByText(/vollständige Neuindizierung/i)).toBeInTheDocument()
  })

  it('creates a model without an API key without a validation error', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()

    renderWithProviders(<LlmModelManagementPage />)
    await user.click(screen.getByRole('button', { name: 'Neues Modell' }))
    const dialog = within(screen.getByRole('dialog'))

    await user.type(dialog.getByLabelText('Anzeigename', { exact: false }), 'Neues Modell')
    await user.type(
      dialog.getByLabelText('Basis-Adresse', { exact: false }),
      'http://localhost:11434/v1',
    )
    await user.type(dialog.getByLabelText('Modell-Kennung', { exact: false }), 'llama3')
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

  /**
   * #759 review: editing must not touch the stored API key at all when the field is left alone,
   * and the field must never show a value - not even after the round trip through save/reload.
   */
  it('changes the display name and saves without touching the stored API key', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()

    renderWithProviders(<LlmModelManagementPage />)
    await waitFor(() => screen.getByText('Ollama lokal'))
    await user.click(screen.getByText('Ollama lokal'))

    const nameField = screen.getByLabelText('Anzeigename', { exact: false })
    await user.clear(nameField)
    await user.type(nameField, 'Ollama umbenannt')
    // The button's aria-label references the model as the server still knows it - the not-yet-
    // saved draft name typed above only takes effect in the label after the save completes.
    await user.click(screen.getByRole('button', { name: '"Ollama lokal" speichern' }))

    await waitFor(() => {
      expect(
        useLlmModelStore.getState().models.find((m) => m.displayName === 'Ollama umbenannt'),
      ).toBeTruthy()
    })
    const saved = useLlmModelStore
      .getState()
      .models.find((m) => m.displayName === 'Ollama umbenannt')!
    expect(saved.apiKeySet).toBe(false)
    expect(screen.getByLabelText('API-Schlüssel (optional)')).toHaveValue('')
  })

  /**
   * #759 review: the panel must stay open and show a visible confirmation after a save - a
   * remount that collapses the card or drops the just-produced message is exactly what this
   * guards against.
   */
  it('keeps the panel open and confirms the save via a status message', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()

    renderWithProviders(<LlmModelManagementPage />)
    await waitFor(() => screen.getByText('Ollama lokal'))
    await user.click(screen.getByText('Ollama lokal'))
    await user.click(screen.getByRole('button', { name: '"Ollama lokal" speichern' }))

    await waitFor(() => {
      expect(screen.getByRole('status')).toHaveTextContent(/wurde gespeichert/i)
    })
    // Still expanded and showing the form, not a remounted, collapsed card.
    expect(screen.getByLabelText('Anzeigename', { exact: false })).toBeVisible()
  })

  /**
   * #759 review: removing a stored key must be reachable through an explicit action, not by
   * inferring "remove" from an untouched empty field (which is indistinguishable from "leave
   * unchanged", since the field never shows the current value either way).
   */
  it('removes a stored API key via the explicit removal action', async () => {
    signInAs('SYSTEM_ADMIN')
    await useLlmModelStore.getState().createNewModel({
      displayName: 'Modell mit Schlüssel',
      baseUrl: 'http://c/v1',
      modelIdentifier: 'c',
      temperature: 0.7,
      maxTokens: 2000,
      apiKey: 'geheim',
    })
    const user = userEvent.setup()

    renderWithProviders(<LlmModelManagementPage />)
    await waitFor(() => screen.getByText('Modell mit Schlüssel'))
    await user.click(screen.getByText('Modell mit Schlüssel'))
    await user.click(
      screen.getByRole('button', {
        name: 'Gespeicherten Schlüssel von "Modell mit Schlüssel" entfernen',
      }),
    )
    await user.click(screen.getByRole('button', { name: '"Modell mit Schlüssel" speichern' }))

    await waitFor(() => {
      expect(
        useLlmModelStore.getState().models.find((m) => m.displayName === 'Modell mit Schlüssel')
          ?.apiKeySet,
      ).toBe(false)
    })
  })

  it('runs a connection test and shows the outcome', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()

    renderWithProviders(<LlmModelManagementPage />)
    await waitFor(() => screen.getByText('Ollama lokal'))
    await user.click(screen.getByText('Ollama lokal'))
    await user.click(screen.getByRole('button', { name: 'Verbindung zu "Ollama lokal" testen' }))

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
    await user.click(screen.getByRole('button', { name: 'Verbindung zu "Ollama lokal" testen' }))

    await waitFor(() => {
      expect(screen.getByText('Modell nicht erreichbar')).toBeInTheDocument()
    })
    expect(screen.getByLabelText('Anzeigename', { exact: false })).toBeEnabled()
  })

  it('rejects deleting the active model client-side, with a visible reason', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()

    renderWithProviders(<LlmModelManagementPage />)
    await waitFor(() => screen.getByText('Ollama lokal'))
    await user.click(screen.getByText('Ollama lokal'))

    const deleteButton = screen.getByRole('button', { name: '"Ollama lokal" löschen' })
    expect(deleteButton).toHaveAttribute('aria-disabled', 'true')
    expect(screen.getByText(/aktive Modell kann nicht gelöscht werden/i)).toBeInTheDocument()

    await user.click(deleteButton)
    expect(
      useLlmModelStore.getState().models.find((m) => m.displayName === 'Ollama lokal'),
    ).toBeTruthy()
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
    await user.click(screen.getByRole('button', { name: '"Modell A" löschen' }))

    await waitFor(() => {
      expect(screen.getByText(/nicht gelöscht werden/i)).toBeInTheDocument()
    })
    confirmSpy.mockRestore()
  })
})
