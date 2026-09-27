import type { BrandingResponse, BrandingUpdateRequest } from '../types/api'
import { apiClient as client, normalizeError } from './api'

// branding is readable by anyone, including the not-yet-signed-in visitor of the sign-in
// page - the backend permits both read paths without authentication (see
// BrandingController). Writing goes through the /v1/system paths below and stays SYSTEM_ADMIN-only.
export async function getBranding(): Promise<BrandingResponse> {
  try {
    const { data } = await client.get<BrandingResponse>('/v1/branding')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateBranding(request: BrandingUpdateRequest): Promise<BrandingResponse> {
  try {
    const { data } = await client.put<BrandingResponse>('/v1/system/branding', request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** The three image slots of the branding settings (#582, #1910), by their path segment. */
export type BrandingImageSlot = 'logo' | 'login-logo' | 'login-background'

export async function uploadBrandingImage(
  slot: BrandingImageSlot,
  file: File,
): Promise<BrandingResponse> {
  try {
    const formData = new FormData()
    formData.append('file', file)
    const { data } = await client.put<BrandingResponse>(`/v1/system/branding/${slot}`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
    return data
  } catch (err) {
    // 'upload' so an oversized image turned away by the reverse proxy's own bare HTML 413 still
    // produces a German message rather than "HTTP 413: ..." - same reasoning as uploadDocument.
    normalizeError(err, 'upload')
  }
}

export async function deleteBrandingImage(slot: BrandingImageSlot): Promise<BrandingResponse> {
  try {
    const { data } = await client.delete<BrandingResponse>(`/v1/system/branding/${slot}`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}
