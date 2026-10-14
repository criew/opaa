import { readStorage, writeStorage } from '@/shared/utils/storage'

const RELOADED_BUILD_KEY = 'opaa.preloadErrorReloadedBuild'

/**
 * After a deploy the chunks of the previous build are gone; a tab that still runs the old entry
 * fails to load the next page. The page is then reloaded once per build, identified by the URL of
 * the entry chunk, which carries its content hash. Every further event of the same document is
 * swallowed while the reload runs, so the loading indicator stays. A build that fails again after
 * its reload, or a session storage that cannot remember the reload, leaves the error to the error
 * boundary: no reload loop.
 */
export function installPreloadErrorReload(
  target: Window = window,
  buildId: string = import.meta.url,
): () => void {
  let reloading = false
  const onPreloadError = (event: Event) => {
    if (reloading) {
      event.preventDefault()
      return
    }
    if (readStorage(target.sessionStorage, RELOADED_BUILD_KEY) === buildId) {
      return
    }
    if (!writeStorage(target.sessionStorage, RELOADED_BUILD_KEY, buildId)) {
      return
    }
    reloading = true
    event.preventDefault()
    target.location.reload()
  }
  target.addEventListener('vite:preloadError', onPreloadError)
  return () => target.removeEventListener('vite:preloadError', onPreloadError)
}
