const LAST_RELOAD_KEY = 'opaa.preloadErrorReloadAt'

/** A second failure within this window after a reload is not answered with another reload. */
export const RELOAD_GUARD_MS = 10_000

/**
 * After a deploy the chunks of the previous build are gone; a tab that still runs the old entry
 * fails to load the next page. The page is then reloaded once to fetch the new build. If loading
 * fails again right after such a reload, the error is left to the error boundary, so a chunk that
 * is missing for good cannot cause a reload loop.
 */
export function installPreloadErrorReload(target: Window = window): () => void {
  const onPreloadError = (event: Event) => {
    const lastReload = Number(target.sessionStorage.getItem(LAST_RELOAD_KEY) ?? 0)
    if (Date.now() - lastReload < RELOAD_GUARD_MS) {
      return
    }
    target.sessionStorage.setItem(LAST_RELOAD_KEY, String(Date.now()))
    event.preventDefault()
    target.location.reload()
  }
  target.addEventListener('vite:preloadError', onPreloadError)
  return () => target.removeEventListener('vite:preloadError', onPreloadError)
}
