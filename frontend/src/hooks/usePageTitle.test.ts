import { renderHook } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { usePageTitle } from './usePageTitle'

describe('usePageTitle', () => {
  it('sets "<title> · OPAA" as the document title', () => {
    renderHook(() => usePageTitle('Einstellungen'))
    expect(document.title).toBe('Einstellungen · OPAA')
  })

  it('falls back to the bare app name without a title', () => {
    renderHook(() => usePageTitle(null))
    expect(document.title).toBe('OPAA')
  })

  it('follows title changes', () => {
    const { rerender } = renderHook(({ title }) => usePageTitle(title), {
      initialProps: { title: 'Gruppen' },
    })
    rerender({ title: 'Wissensbibliotheken' })
    expect(document.title).toBe('Wissensbibliotheken · OPAA')
  })
})
