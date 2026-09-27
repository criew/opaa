import type { BrandingResponse } from '../types/api'

/**
 * Mutable so the handlers can reflect a PUT back on the next GET - the branding form's whole point
 * is that a change is immediately in effect, and a frozen fixture would make the mock disagree
 * with the product about exactly that.
 */
export let mockBranding: BrandingResponse = {
  productName: 'OPAA',
  claim: 'Fragen. Belegen. Entscheiden.',
  primaryColor: '#1292EE',
  defaultColorScheme: 'SYSTEM',
}

export function setMockBranding(branding: BrandingResponse) {
  mockBranding = branding
}

export function resetMockBranding() {
  mockBranding = {
    productName: 'OPAA',
    claim: 'Fragen. Belegen. Entscheiden.',
    primaryColor: '#1292EE',
    defaultColorScheme: 'SYSTEM',
  }
}
