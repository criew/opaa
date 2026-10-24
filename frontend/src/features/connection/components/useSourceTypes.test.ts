import { act, renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { mockSourceTypes } from '@/features/library/mocks/libraryFixtures'
import { server } from '@/test/server'
import { useSourceTypes } from './useSourceTypes'

describe('useSourceTypes', () => {
  it('loads the source types the backend has a connector for', async () => {
    const { result } = renderHook(() => useSourceTypes())

    expect(result.current).toMatchObject({ sourceTypes: [], error: null, loaded: false })
    await waitFor(() => expect(result.current.loaded).toBe(true))
    expect(result.current).toMatchObject({
      sourceTypes: mockSourceTypes,
      error: null,
      loaded: true,
    })
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

  it('asks again on reload after a failed request', async () => {
    server.use(
      http.get('/api/v1/source-types', () => HttpResponse.json({}, { status: 502 }), {
        once: true,
      }),
    )

    const { result } = renderHook(() => useSourceTypes())
    await waitFor(() => expect(result.current.error).toBeTruthy())

    act(() => result.current.reload())
    await waitFor(() => expect(result.current.sourceTypes).toEqual(mockSourceTypes))
    expect(result.current.error).toBeNull()
  })
})
