import { afterEach, describe, expect, it } from 'vitest'
import { SLOW_SCENARIO_DELAY_MS, slowScenarioDelayMs } from './mockScenario'

describe('slowScenarioDelayMs', () => {
  afterEach(() => {
    window.sessionStorage.removeItem('opaa.mockSlowMs')
  })

  it('uses the default latency without an override', () => {
    expect(slowScenarioDelayMs()).toBe(SLOW_SCENARIO_DELAY_MS)
  })

  it('takes a positive whole number of milliseconds from the session', () => {
    window.sessionStorage.setItem('opaa.mockSlowMs', '600000')
    expect(slowScenarioDelayMs()).toBe(600000)
  })

  it.each(['0', '-5', '1.5', 'lange'])('ignores the invalid override %s', (value) => {
    window.sessionStorage.setItem('opaa.mockSlowMs', value)
    expect(slowScenarioDelayMs()).toBe(SLOW_SCENARIO_DELAY_MS)
  })
})
