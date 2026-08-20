import { renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import { usePageTitle } from './usePageTitle'
import { OPAA_BRANDING, useBrandingStore } from '../stores/brandingStore'

describe('usePageTitle', () => {
  beforeEach(() => {
    useBrandingStore.setState({ branding: OPAA_BRANDING })
  })

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

  /** #583: the tab follows the operator's product name, not a compiled-in constant. */
  it('uses the configured product name', () => {
    useBrandingStore.setState({
      branding: { ...OPAA_BRANDING, productName: 'Landesamt-Assistent' },
    })

    renderHook(() => usePageTitle('Gruppen'))

    expect(document.title).toBe('Gruppen · Landesamt-Assistent')
  })

  it('follows a branding change without a route change', () => {
    const { rerender } = renderHook(() => usePageTitle('Gruppen'))
    expect(document.title).toBe('Gruppen · OPAA')

    useBrandingStore.setState({
      branding: { ...OPAA_BRANDING, productName: 'Landesamt-Assistent' },
    })
    rerender()

    expect(document.title).toBe('Gruppen · Landesamt-Assistent')
  })
})
