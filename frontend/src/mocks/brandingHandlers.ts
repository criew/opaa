import { http, HttpResponse } from 'msw'
import { mockBranding, setMockBranding } from './brandingFixtures'
import type { BrandingUpdateRequest } from '../types/api'

export const brandingHandlers = [
  // readable without authentication, like the real endpoint - the sign-in page renders
  // before there is a session and still shows the operator's mark.
  http.get('/api/v1/branding', () => {
    return HttpResponse.json(mockBranding)
  }),

  // Mirrors the backend's replace-everything semantics: a field that arrives empty or
  // absent means "back to the OPAA default", not "leave the current value alone".
  http.put('/api/v1/system/branding', async ({ request }) => {
    const body = (await request.json()) as BrandingUpdateRequest
    const primaryColor = body.primaryColor?.trim() ?? ''
    if (primaryColor !== '' && !/^#[0-9A-Fa-f]{6}$/.test(primaryColor)) {
      return HttpResponse.json(
        {
          error:
            "Die Primärfarbe muss ein sechsstelliger Hex-Wert mit führendem '#' sein, zum Beispiel #1292EE",
          status: 400,
          timestamp: new Date().toISOString(),
        },
        { status: 400 },
      )
    }
    setMockBranding({
      ...mockBranding,
      productName: body.productName?.trim() || 'OPAA',
      claim: body.claim?.trim() || 'Fragen. Belegen. Entscheiden.',
      primaryColor: primaryColor || '#1292EE',
      defaultColorScheme: body.defaultColorScheme ?? 'SYSTEM',
    })
    return HttpResponse.json(mockBranding)
  }),

  http.put('/api/v1/system/branding/logo', () => {
    setMockBranding({
      ...mockBranding,
      logoUrl: '/api/v1/branding/logo?v=mocklogo',
      logoContentType: 'image/png',
      logoUpdatedAt: new Date().toISOString(),
    })
    return HttpResponse.json(mockBranding)
  }),

  http.delete('/api/v1/system/branding/logo', () => {
    const { logoUrl, logoContentType, logoUpdatedAt, ...withoutLogo } = mockBranding
    void logoUrl
    void logoContentType
    void logoUpdatedAt
    setMockBranding(withoutLogo)
    return HttpResponse.json(mockBranding)
  }),

  http.put('/api/v1/system/branding/login-logo', () => {
    setMockBranding({
      ...mockBranding,
      loginLogoUrl: '/api/v1/branding/login-logo?v=mockloginlogo',
      loginLogoContentType: 'image/png',
      loginLogoUpdatedAt: new Date().toISOString(),
    })
    return HttpResponse.json(mockBranding)
  }),

  http.delete('/api/v1/system/branding/login-logo', () => {
    const { loginLogoUrl, loginLogoContentType, loginLogoUpdatedAt, ...rest } = mockBranding
    void loginLogoUrl
    void loginLogoContentType
    void loginLogoUpdatedAt
    setMockBranding(rest)
    return HttpResponse.json(mockBranding)
  }),

  http.put('/api/v1/system/branding/login-background', () => {
    setMockBranding({
      ...mockBranding,
      loginBackgroundUrl: '/api/v1/branding/login-background?v=mockbackground',
      loginBackgroundContentType: 'image/jpeg',
      loginBackgroundUpdatedAt: new Date().toISOString(),
    })
    return HttpResponse.json(mockBranding)
  }),

  http.delete('/api/v1/system/branding/login-background', () => {
    const { loginBackgroundUrl, loginBackgroundContentType, loginBackgroundUpdatedAt, ...rest } =
      mockBranding
    void loginBackgroundUrl
    void loginBackgroundContentType
    void loginBackgroundUpdatedAt
    setMockBranding(rest)
    return HttpResponse.json(mockBranding)
  }),
]
