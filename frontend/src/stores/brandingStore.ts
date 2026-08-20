import { create } from 'zustand'
import type { BrandingResponse, BrandingUpdateRequest } from '../types/api'
import {
  deleteBrandingLogo,
  getBranding,
  updateBranding,
  uploadBrandingLogo,
} from '../services/api'

/**
 * The OPAA standard, mirroring `io.opaa.branding.BrandingDefaults` in the backend. Kept as a
 * literal here rather than fetched, because it has to be available before any request completes -
 * it is what the interface renders during the first paint and what it falls back to when the
 * request fails (#583: "bei Fehlern oder fehlender Konfiguration stiller Rückfall auf den
 * OPAA-Standard").
 *
 * The backend resolves the same defaults field by field, so a successful response never contains
 * a gap this has to fill. This exists for the case where there is no response at all.
 */
export const OPAA_BRANDING: BrandingResponse = {
  productName: 'OPAA',
  claim: 'Fragen. Belegen. Entscheiden.',
  // #634: blue[700], not blue[500] - the lighter step only reaches 3.29:1 against white button
  // text, below the 4.5:1 WCAG minimum for normal text (frontend/src/theme/tokens.ts).
  primaryColor: '#0B6FBC',
  defaultColorScheme: 'SYSTEM',
}

interface BrandingState {
  branding: BrandingResponse
  /** False until the first load attempt has settled - see `loadBranding`. */
  isLoaded: boolean
  isSaving: boolean
  error: string | null
  loadBranding: () => Promise<void>
  saveBranding: (request: BrandingUpdateRequest) => Promise<void>
  saveLogo: (file: File) => Promise<void>
  removeLogo: () => Promise<void>
  clearError: () => void
}

export const useBrandingStore = create<BrandingState>((set) => ({
  branding: OPAA_BRANDING,
  isLoaded: false,
  isSaving: false,
  error: null,

  /**
   * Loads the operator's branding. **Never surfaces an error**: branding is decoration, and a
   * failed request must not keep the application from rendering or push a message in front of a
   * user who cannot act on it. The store simply keeps {@link OPAA_BRANDING} and marks itself
   * loaded, so the interface looks exactly like an unconfigured deployment.
   *
   * Deliberately not guarded by the session epoch the other stores use: this runs before sign-in
   * (the sign-in page needs it) and is not user-scoped, so there is no previous user's data it
   * could resurrect.
   */
  loadBranding: async () => {
    try {
      set({ branding: await getBranding(), isLoaded: true })
    } catch {
      set({ isLoaded: true })
    }
  },

  saveBranding: async (request: BrandingUpdateRequest) => {
    set({ isSaving: true, error: null })
    try {
      set({ branding: await updateBranding(request), isSaving: false })
    } catch (err) {
      const message =
        err instanceof Error ? err.message : 'Branding konnte nicht gespeichert werden'
      set({ error: message, isSaving: false })
      throw err
    }
  },

  saveLogo: async (file: File) => {
    set({ isSaving: true, error: null })
    try {
      set({ branding: await uploadBrandingLogo(file), isSaving: false })
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Logo konnte nicht gespeichert werden'
      set({ error: message, isSaving: false })
      throw err
    }
  },

  removeLogo: async () => {
    set({ isSaving: true, error: null })
    try {
      set({ branding: await deleteBrandingLogo(), isSaving: false })
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Logo konnte nicht entfernt werden'
      set({ error: message, isSaving: false })
      throw err
    }
  },

  clearError: () => set({ error: null }),
}))
