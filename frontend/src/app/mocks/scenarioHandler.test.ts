import { afterEach, describe, expect, it } from 'vitest'
import { emptied } from './scenarioHandler'

function useScenario(name: string) {
  window.sessionStorage.setItem('opaa.mockScenario', name)
}

/** The scenarios `empty`, `slow` and `failed` for every topic, through the regular handlers. */
describe('scenario handler', () => {
  afterEach(() => {
    window.sessionStorage.removeItem('opaa.mockScenario')
    window.sessionStorage.removeItem('opaa.mockSlowMs')
  })

  it('leaves the regular fixtures alone in the default scenario', async () => {
    const response = await fetch('/api/v1/prompt-libraries')
    expect(response.status).toBe(200)
    expect(((await response.json()) as unknown[]).length).toBeGreaterThan(0)
  })

  it('answers a listing of any topic with zero entries in `empty`', async () => {
    useScenario('empty')

    const response = await fetch('/api/v1/prompt-libraries')

    expect(response.status).toBe(200)
    expect(await response.json()).toEqual([])
  })

  it('empties a paged listing and sets its totals to zero', () => {
    expect(
      emptied({ items: [{ id: 'a' }], totalElements: 4, totalPages: 1, hasMore: true, page: 0 }),
    ).toEqual({ items: [], totalElements: 0, totalPages: 0, hasMore: false, page: 0 })
  })

  it('fails a listing of any topic with 502 in `failed`', async () => {
    useScenario('failed')

    const response = await fetch('/api/v1/prompt-libraries')

    expect(response.status).toBe(502)
    expect(await response.json()).toMatchObject({ status: 502 })
  })

  it('holds a request back for the latency of `slow`', async () => {
    useScenario('slow')
    window.sessionStorage.setItem('opaa.mockSlowMs', '120')

    const started = Date.now()
    const response = await fetch('/api/v1/prompt-libraries')

    expect(Date.now() - started).toBeGreaterThanOrEqual(100)
    expect(response.status).toBe(200)
  })

  it('never touches what the shell needs to start', async () => {
    useScenario('failed')

    expect((await fetch('/api/health')).status).toBe(200)
    expect((await fetch('/api/v1/auth/config')).status).toBe(200)
  })
})
