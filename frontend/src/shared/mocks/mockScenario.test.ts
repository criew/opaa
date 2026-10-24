import { afterEach, describe, expect, it } from 'vitest'
import { SLOW_SCENARIO_DELAY_MS, currentMockRole, slowScenarioDelayMs } from './mockScenario'

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

describe('currentMockRole', () => {
  afterEach(() => {
    window.sessionStorage.removeItem('opaa.mockRole')
    window.history.replaceState(null, '', '/')
  })

  it('keeps the fixture roles without an override', () => {
    expect(currentMockRole()).toBeNull()
  })

  it('takes the role from the query in any case and remembers it', () => {
    window.history.replaceState(null, '', '/libraries/x?mockRole=viewer')
    expect(currentMockRole()).toBe('VIEWER')
    window.history.replaceState(null, '', '/libraries/x')
    expect(currentMockRole()).toBe('VIEWER')
  })

  it('drops the remembered role on default and ignores unknown roles', () => {
    window.sessionStorage.setItem('opaa.mockRole', 'EDITOR')
    window.history.replaceState(null, '', '/?mockRole=chef')
    expect(currentMockRole()).toBe('EDITOR')
    window.history.replaceState(null, '', '/?mockRole=default')
    expect(currentMockRole()).toBeNull()
  })
})
