import { renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { mockSourceTypes } from '../../../mocks/libraryFixtures'
import { server } from '../../../mocks/server'
import { useSourceTypes } from './useSourceTypes'

describe('useSourceTypes', () => {
  it('loads the source types the backend has a connector for', async () => {
    const { result } = renderHook(() => useSourceTypes())

    expect(result.current).toEqual({ sourceTypes: [], error: null, loaded: false })
    await waitFor(() => expect(result.current.loaded).toBe(true))
    expect(result.current).toEqual({ sourceTypes: mockSourceTypes, error: null, loaded: true })
  })

  it('reports a failed request as loaded, with its reason and no types', async () => {
    server.use(
      http.get('/api/v1/source-types', () =>
        HttpResponse.json(
          { status: 500, error: 'Internal Server Error', message: 'Quellarten nicht lesbar' },
          { status: 500 },
        ),
      ),
    )

    const { result } = renderHook(() => useSourceTypes())

    await waitFor(() => expect(result.current.loaded).toBe(true))
    expect(result.current.sourceTypes).toEqual([])
    expect(result.current.error).toBeTruthy()
  })
})
