import { useEffect } from 'react'
import { confirmAction } from '../../stores/confirmStore'

/** The link a click would follow inside the application, or `null`. */
function followedLink(event: MouseEvent): HTMLAnchorElement | null {
  if (event.defaultPrevented || event.button !== 0) return null
  if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return null
  const anchor = event.target instanceof Element ? event.target.closest('a[href]') : null
  if (!(anchor instanceof HTMLAnchorElement)) return null
  if ((anchor.target && anchor.target !== '_self') || anchor.hasAttribute('download')) return null
  const target = new URL(anchor.href, window.location.href)
  if (target.origin !== window.location.origin) return null
  const here = window.location
  const samePage = target.pathname === here.pathname && target.search === here.search
  // a hash route („#/…“) is a place of its own, a plain hash only a position on this page
  if (samePage && (target.hash === here.hash || !target.hash.startsWith('#/'))) return null
  return anchor
}

/**
 * While `dirty`, leaving through a link of the application asks „Änderungen verwerfen?“ first
 * (guidelines 5.16), and closing or reloading the tab asks through the browser. A declined
 * question keeps the page and its entries; a confirmed one follows the link.
 */
export function useLeaveGuard(dirty: boolean) {
  useEffect(() => {
    if (!dirty) return
    let confirmedLink: HTMLAnchorElement | null = null

    function onClick(event: MouseEvent) {
      const anchor = followedLink(event)
      if (!anchor) return
      if (anchor === confirmedLink) {
        confirmedLink = null
        return
      }
      event.preventDefault()
      event.stopPropagation()
      void confirmAction({
        question: 'Änderungen verwerfen?',
        consequence:
          'Die Eingaben auf dieser Seite sind noch nicht gespeichert und gehen verloren.',
        confirmLabel: 'Verwerfen',
        tone: 'neutral',
      }).then((confirmed) => {
        if (!confirmed) return
        confirmedLink = anchor
        anchor.click()
      })
    }

    function onBeforeUnload(event: BeforeUnloadEvent) {
      event.preventDefault()
    }

    // capture on the document: before the router's own handler on the link sees the click
    document.addEventListener('click', onClick, true)
    window.addEventListener('beforeunload', onBeforeUnload)
    return () => {
      document.removeEventListener('click', onClick, true)
      window.removeEventListener('beforeunload', onBeforeUnload)
    }
  }, [dirty])
}
