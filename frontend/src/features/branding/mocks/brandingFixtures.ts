import type { BrandingResponse } from '@/shared/api/types'
import { OPAA_BRANDING } from '@/shared/branding/brandingStore'

/**
 * Mutable so the handlers can reflect a PUT back on the next GET - the branding form's whole point
 * is that a change is immediately in effect, and a frozen fixture would make the mock disagree
 * with the product about exactly that.
 */
export let mockBranding: BrandingResponse = { ...OPAA_BRANDING }

export function setMockBranding(branding: BrandingResponse) {
  mockBranding = branding
}

export function resetMockBranding() {
  mockBranding = { ...OPAA_BRANDING }
}
