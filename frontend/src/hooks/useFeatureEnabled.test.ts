import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { useFeatureEnabled } from './useFeatureEnabled'
import { useAuthStore } from '../stores/authStore'

describe('useFeatureEnabled', () => {
  beforeEach(() => {
    useAuthStore.setState({ features: {} })
  })

  it('reports a flag the configuration does not name as on', () => {
    const { result } = renderHook(() => useFeatureEnabled('metadata'))
    expect(result.current).toBe(true)
  })

  it('reports a switched-off flag as off and leaves the others on', () => {
    useAuthStore.setState({ features: { metadata: false, promptLibraries: true } })

    expect(renderHook(() => useFeatureEnabled('metadata')).result.current).toBe(false)
    expect(renderHook(() => useFeatureEnabled('promptLibraries')).result.current).toBe(true)
  })

  it('follows the store when the configuration changes', () => {
    const { result } = renderHook(() => useFeatureEnabled('promptLibraries'))
    expect(result.current).toBe(true)

    act(() => useAuthStore.setState({ features: { promptLibraries: false } }))

    expect(result.current).toBe(false)
  })
})
