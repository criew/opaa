import { useEffect } from 'react'

export const APP_TITLE = 'OPAA'

/** Sets the document title to "<title> · OPAA" so route changes are reflected in the tab and by screen readers. */
export function usePageTitle(title: string | null | undefined): void {
  useEffect(() => {
    document.title = title ? `${title} · ${APP_TITLE}` : APP_TITLE
  }, [title])
}
