import { beforeEach, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import { OPAA_BRANDING, useBrandingStore } from './brandingStore'

describe('brandingStore', () => {
  beforeEach(() => {
    useBrandingStore.setState({
      branding: OPAA_BRANDING,
      isLoaded: false,
      isSaving: false,
      error: null,
    })
  })

  it('starts on the OPAA standard before anything is loaded', () => {
    const { branding, isLoaded } = useBrandingStore.getState()

    expect(branding).toEqual(OPAA_BRANDING)
    expect(isLoaded).toBe(false)
  })

  it('adopts the configured branding', async () => {
    server.use(
      http.get('/api/v1/branding', () =>
        HttpResponse.json({
          productName: 'Landesamt-Assistent',
          claim: 'Kurz und klar',
          primaryColor: '#0B6FBC',
          defaultColorScheme: 'DARK',
          logoUrl: '/api/v1/branding/logo?v=abc123',
        }),
      ),
    )

    await useBrandingStore.getState().loadBranding()

    const { branding, isLoaded } = useBrandingStore.getState()
    expect(branding.productName).toBe('Landesamt-Assistent')
    expect(branding.defaultColorScheme).toBe('DARK')
    expect(branding.logoUrl).toBe('/api/v1/branding/logo?v=abc123')
    expect(isLoaded).toBe(true)
  })

  /**
   * #583: "bei Fehlern oder fehlender Konfiguration stiller Rückfall auf den OPAA-Standard".
   * Silent is the operative word - branding is decoration, and a failed request must neither keep
   * the application from rendering nor put a message in front of a user who cannot act on it.
   */
  it('falls back to the OPAA standard silently when the request fails', async () => {
    server.use(http.get('/api/v1/branding', () => HttpResponse.error()))

    await useBrandingStore.getState().loadBranding()

    const { branding, isLoaded, error } = useBrandingStore.getState()
    expect(branding).toEqual(OPAA_BRANDING)
    expect(isLoaded).toBe(true)
    expect(error).toBeNull()
  })

  it('falls back just as silently on a server error', async () => {
    server.use(http.get('/api/v1/branding', () => new HttpResponse(null, { status: 500 })))

    await useBrandingStore.getState().loadBranding()

    expect(useBrandingStore.getState().branding).toEqual(OPAA_BRANDING)
    expect(useBrandingStore.getState().error).toBeNull()
  })

  it('applies a saved change immediately, without a second request', async () => {
    await useBrandingStore
      .getState()
      .saveBranding({ productName: 'Landesamt-Assistent', primaryColor: '#0B6FBC' })

    const { branding, isSaving, error } = useBrandingStore.getState()
    expect(branding.productName).toBe('Landesamt-Assistent')
    expect(branding.primaryColor).toBe('#0B6FBC')
    expect(isSaving).toBe(false)
    expect(error).toBeNull()
  })

  it('empties a field back to the OPAA standard rather than storing the empty value', async () => {
    await useBrandingStore.getState().saveBranding({ productName: 'Landesamt-Assistent' })
    await useBrandingStore.getState().saveBranding({ productName: '' })

    expect(useBrandingStore.getState().branding.productName).toBe(OPAA_BRANDING.productName)
  })

  /**
   * Unlike loading, saving is an action the operator just took - a failure there has to be visible,
   * or they are left believing a change took effect that did not.
   */
  it('surfaces a rejected change instead of swallowing it', async () => {
    await expect(
      useBrandingStore.getState().saveBranding({ primaryColor: 'blau' }),
    ).rejects.toThrow()

    const { error, isSaving, branding } = useBrandingStore.getState()
    expect(error).toContain('Primärfarbe')
    expect(isSaving).toBe(false)
    expect(branding.primaryColor).toBe(OPAA_BRANDING.primaryColor)
  })

  it('stores and removes a logo', async () => {
    const file = new File([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], 'logo.png', {
      type: 'image/png',
    })

    await useBrandingStore.getState().saveLogo(file)
    expect(useBrandingStore.getState().branding.logoUrl).toBeTruthy()

    await useBrandingStore.getState().removeLogo()
    expect(useBrandingStore.getState().branding.logoUrl).toBeUndefined()
  })
})
