import { screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../../../mocks/server'
import { renderWithProviders } from '../../../test/test-utils'
import LimitedSearchNotice from './LimitedSearchNotice'

const ENDPOINT = '/api/v1/search/availability'

describe('LimitedSearchNotice', () => {
  it('tells the reader that the search is limited while the vector search is suspended', async () => {
    server.use(http.get(ENDPOINT, () => HttpResponse.json({ vectorSearch: 'SUSPENDED' })))

    renderWithProviders(<LimitedSearchNotice />)

    expect(await screen.findByText(/Eingeschränkte Suche/)).toBeInTheDocument()
  })

  it('stays silent while the vector search answers', async () => {
    let asked = false
    server.use(
      http.get(ENDPOINT, () => {
        asked = true
        return HttpResponse.json({ vectorSearch: 'AVAILABLE' })
      }),
    )

    renderWithProviders(<LimitedSearchNotice />)

    await waitFor(() => expect(asked).toBe(true))
    expect(screen.queryByText(/Eingeschränkte Suche/)).not.toBeInTheDocument()
  })

  it('stays silent when the availability cannot be read', async () => {
    let asked = false
    server.use(
      http.get(ENDPOINT, () => {
        asked = true
        return HttpResponse.json({ error: 'kaputt' }, { status: 500 })
      }),
    )

    renderWithProviders(<LimitedSearchNotice />)

    await waitFor(() => expect(asked).toBe(true))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})
