import { useEffect } from 'react'
import { useBrandingStore } from '../stores/brandingStore'

/**
 * The product name a page title is suffixed with. Only a fallback for callers outside React
 * (and for tests asserting the unbranded default) - the hook below reads the configured name
 * from the branding store, so the document title follows the operator's product name (#583).
 */
export const APP_TITLE = 'OPAA'

/** Sets the document title to "<title> · <Produktname>" so route changes are reflected in the tab and by screen readers. */
export function usePageTitle(title: string | null | undefined): void {
  const productName = useBrandingStore((s) => s.branding.productName)

  useEffect(() => {
    document.title = title ? `${title} · ${productName}` : productName
  }, [title, productName])
}
