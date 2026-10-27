import { screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import { renderWithProviders, setMockAuthState } from '@/test/test-utils'
import { useAuthStore } from '@/shared/session/authStore'
import SearchAvailabilityNotice from './SearchAvailabilityNotice'

const ENDPOINT = '/api/v1/search/availability'

function availability(overrides: Record<string, unknown>) {
  return HttpResponse.json({
    vectorSearch: 'AVAILABLE',
    chatModelConfigured: true,
    embeddingModelConfigured: true,
    ...overrides,
  })
}

function signInAs(systemRole: 'USER' | 'SYSTEM_ADMIN') {
  setMockAuthState()
  useAuthStore.setState({
    user: { id: 'u-1', email: 'person@stadt.example', displayName: 'Person', systemRole },
  })
}

describe('SearchAvailabilityNotice', () => {
  afterEach(() => {
    useAuthStore.setState({ user: null })
  })

  it('tells the reader that the search is limited while the vector search is suspended', async () => {
    server.use(http.get(ENDPOINT, () => availability({ vectorSearch: 'SUSPENDED' })))

    renderWithProviders(<SearchAvailabilityNotice />)

    expect(await screen.findByText(/Eingeschränkte Suche/)).toBeInTheDocument()
  })

  it('stays silent while the vector search answers', async () => {
    let asked = false
    server.use(
      http.get(ENDPOINT, () => {
        asked = true
        return availability({})
      }),
    )

    renderWithProviders(<SearchAvailabilityNotice />)

    await waitFor(() => expect(asked).toBe(true))
    expect(screen.queryByText(/Eingeschränkte Suche/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Noch kein/)).not.toBeInTheDocument()
  })

  it('stays silent when the availability cannot be read', async () => {
    let asked = false
    server.use(
      http.get(ENDPOINT, () => {
        asked = true
        return HttpResponse.json({ error: 'kaputt' }, { status: 500 })
      }),
    )

    renderWithProviders(<SearchAvailabilityNotice />)

    await waitFor(() => expect(asked).toBe(true))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('names a missing chat model without a way in for a person without administration', async () => {
    signInAs('USER')
    server.use(http.get(ENDPOINT, () => availability({ chatModelConfigured: false })))

    renderWithProviders(<SearchAvailabilityNotice />, { withRouter: true })

    expect(await screen.findByText(/Noch kein Chat-Modell eingerichtet/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Chat-Modell einrichten' })).not.toBeInTheDocument()
  })

  it('leads a system administrator to the missing models', async () => {
    signInAs('SYSTEM_ADMIN')
    server.use(
      http.get(ENDPOINT, () =>
        availability({ chatModelConfigured: false, embeddingModelConfigured: false }),
      ),
    )

    renderWithProviders(<SearchAvailabilityNotice />, { withRouter: true })

    expect(await screen.findByRole('link', { name: 'Chat-Modell einrichten' })).toHaveAttribute(
      'href',
      '/admin/models/chat',
    )
    expect(screen.getByRole('link', { name: 'Embedding-Modell festlegen' })).toHaveAttribute(
      'href',
      '/admin/models/embedding',
    )
    expect(screen.getByText(/Noch kein Embedding-Modell eingerichtet/)).toBeInTheDocument()
  })
})
