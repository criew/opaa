import { renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { usePageTitle } from './usePageTitle'
import { OPAA_BRANDING, useBrandingStore } from '../branding/brandingStore'

describe('usePageTitle', () => {
  beforeEach(() => {
    useBrandingStore.setState({ branding: OPAA_BRANDING })
  })

  it('sets "<title> · opaa" as the document title', () => {
    renderHook(() => usePageTitle('Einstellungen'))
    expect(document.title).toBe('Einstellungen · opaa')
  })

  it('falls back to the bare app name without a title', () => {
    renderHook(() => usePageTitle(null))
    expect(document.title).toBe('opaa')
  })

  it('follows title changes', () => {
    const { rerender } = renderHook(({ title }) => usePageTitle(title), {
      initialProps: { title: 'Gruppen' },
    })
    rerender({ title: 'Wissensbibliotheken' })
    expect(document.title).toBe('Wissensbibliotheken · opaa')
  })

  /** The tab follows the operator's product name, not a compiled-in constant. */
  it('uses the configured product name', () => {
    useBrandingStore.setState({
      branding: { ...OPAA_BRANDING, productName: 'Landesamt-Assistent' },
    })

    renderHook(() => usePageTitle('Gruppen'))

    expect(document.title).toBe('Gruppen · Landesamt-Assistent')
  })

  it('follows a branding change without a route change', () => {
    const { rerender } = renderHook(() => usePageTitle('Gruppen'))
    expect(document.title).toBe('Gruppen · opaa')

    useBrandingStore.setState({
      branding: { ...OPAA_BRANDING, productName: 'Landesamt-Assistent' },
    })
    rerender()

    expect(document.title).toBe('Gruppen · Landesamt-Assistent')
  })
})
