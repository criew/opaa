import { afterEach, describe, expect, it } from 'vitest'
import { setMockAnswerDeltas } from './queryHandlers'

async function millisecondsToFirstDelta(): Promise<number> {
  const startedAt = performance.now()
  const response = await fetch('/api/v1/query/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question: 'Wie richte ich die Umgebung ein?' }),
  })
  const reader = response.body!.getReader()
  const decoder = new TextDecoder()
  let received = ''
  while (!received.includes('event: delta')) {
    const { done, value } = await reader.read()
    if (done) break
    received += decoder.decode(value, { stream: true })
  }
  await reader.cancel()
  return performance.now() - startedAt
}

describe('mock answer stream', () => {
  afterEach(() => {
    window.sessionStorage.removeItem('opaa.mockScenario')
    setMockAnswerDeltas(false)
  })

  it('sends the first word at once in the default scenario', async () => {
    setMockAnswerDeltas(true)

    expect(await millisecondsToFirstDelta()).toBeLessThan(1000)
  })

  it('holds the first word back in the slow scenario', async () => {
    setMockAnswerDeltas(true)
    window.sessionStorage.setItem('opaa.mockScenario', 'slow')

    expect(await millisecondsToFirstDelta()).toBeGreaterThanOrEqual(1900)
  })

  it("streams an agent's steps before its answer", async () => {
    const response = await fetch('/api/v1/query/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        question: 'Was regelt die Dienstvereinbarung?',
        agentId: '00000000-0000-4000-8000-00000000a9e1',
      }),
    })
    const events = [...(await response.text()).matchAll(/^event: (\S+)$/gm)].map((m) => m[1])

    expect(events).toEqual([
      'started',
      'run_started',
      'step_started',
      'step_finished',
      'step_started',
      'step_finished',
      'step_started',
      'step_finished',
      'run_finished',
      'final',
    ])
  })
})
