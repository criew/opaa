import { beforeEach, describe, expect, it } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { AxiosError, AxiosHeaders } from 'axios'
import {
  RECENT_LOGIN_NOT_CONFIRMED_BY_PROVIDER_MESSAGE,
  consumeRecentLoginReturn,
  markRecentLoginReturn,
} from './recentLogin'
import { resetProviderReturn, useProviderReturn, useRecentLoginGate } from './useRecentLoginGate'

function refusal(): AxiosError {
  const config = { headers: new AxiosHeaders() }
  return new AxiosError('Request failed', 'ERR_BAD_REQUEST', config, null, {
    status: 403,
    statusText: 'Forbidden',
    headers: {},
    config,
    data: { error: 'x', status: 403, timestamp: '', code: 'RECENT_LOGIN_REQUIRED' },
  })
}

describe('useRecentLoginGate', () => {
  beforeEach(() => {
    consumeRecentLoginReturn()
    resetProviderReturn()
  })

  it('reports a return from the provider while the page that took it is mounted', () => {
    markRecentLoginReturn()
    renderHook(() => useProviderReturn('zurück'))
    const gate = renderHook(() => useRecentLoginGate('Zweck'))

    let answer: string | null = null
    act(() => {
      answer = gate.result.current.refusal(refusal(), () => {}, false)
    })
    expect(answer).toBe(RECENT_LOGIN_NOT_CONFIRMED_BY_PROVIDER_MESSAGE)
  })

  it('drops the return from the provider when the page that took it is left', () => {
    markRecentLoginReturn()
    const page = renderHook(() => useProviderReturn('zurück'))
    page.unmount()
    const gate = renderHook(() => useRecentLoginGate('Zweck'))

    let answer: string | null = null
    act(() => {
      answer = gate.result.current.refusal(refusal(), () => {}, false)
    })
    expect(answer).toBe('')
  })
})
