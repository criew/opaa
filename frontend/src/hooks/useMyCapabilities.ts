import { useEffect, useState } from 'react'
import { getMyCapabilities } from '../services/api'
import type { Capability } from '../types/api'

interface UseMyCapabilitiesResult {
  /** `null` until the answer is in - neither "held" nor "missing" is known before that. */
  capabilities: Capability[] | null
  /**
   * Whether the caller is known to lack the capability. Deliberately not the negation of "holds
   * it": while the answer is still outstanding - or after a failed load - this is `false`, so the
   * dialog behaves exactly as it did before this hook existed and lets the backend answer.
   */
  isMissing: (capability: Capability) => boolean
}

/**
 * The caller's own Anlegerechte, loaded once per mount. The creation dialogs use it to *explain* a
 * missing right instead of hiding the button (ADR-0036, Entscheidung 5); it never replaces the
 * backend check, which evaluates the capability per request and refuses with the code
 * `CAPABILITY_REQUIRED`.
 */
export function useMyCapabilities(): UseMyCapabilitiesResult {
  const [capabilities, setCapabilities] = useState<Capability[] | null>(null)

  useEffect(() => {
    let active = true
    void getMyCapabilities()
      .then((held) => {
        if (active) setCapabilities(held)
      })
      .catch(() => {
        if (active) setCapabilities(null)
      })
    return () => {
      active = false
    }
  }, [])

  return {
    capabilities,
    isMissing: (capability) => capabilities !== null && !capabilities.includes(capability),
  }
}
