import { lazy, Suspense } from 'react'
import { render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import ErrorBoundary from './ErrorBoundary'
import PageLoading from './shell/PageLoading'
import { loadOrWait } from './lazyPages'
import { installPreloadErrorReload } from './preloadErrorReload'
import { loadOptionalChunk } from '@/shared/utils/optionalChunk'

const BUILD = 'https://opaa.example/assets/index-AAAA.js'

/** A window stand-in: real event dispatch, a session storage shared across reloads, a reload spy. */
function fakeWindow(storage = new Map<string, string>(), { storageFails = false } = {}) {
  const reload = vi.fn()
  const fail = () => {
    throw new DOMException('blocked', 'SecurityError')
  }
  const win = Object.assign(new EventTarget(), {
    sessionStorage: {
      getItem: (key: string) => (storageFails ? fail() : (storage.get(key) ?? null)),
      setItem: (key: string, value: string) => (storageFails ? fail() : storage.set(key, value)),
      removeItem: (key: string) => storage.delete(key),
    },
    location: { reload },
  }) as unknown as Window
  return { win, reload, storage }
}

function preloadError(win: Window) {
  const event = new Event('vite:preloadError', { cancelable: true })
  win.dispatchEvent(event)
  return event
}

/** Mirrors Vite's preload helper: a failed import dispatches the event and rethrows unless prevented. */
function vitePreload<T>(win: Window, load: () => Promise<T>): () => Promise<T> {
  return () =>
    load().catch((error: unknown) => {
      if (!preloadError(win).defaultPrevented) throw error
      return undefined as T
    })
}

const missingChunk = () =>
  Promise.reject(new TypeError('Failed to fetch dynamically imported module'))

function renderPage(win: Window) {
  const Page = lazy(() => loadOrWait(vitePreload(win, missingChunk)))
  return render(
    <ErrorBoundary>
      <Suspense fallback={<PageLoading />}>
        <Page />
      </Suspense>
    </ErrorBoundary>,
  )
}

describe('installPreloadErrorReload', () => {
  it('reloads once and swallows the error when a chunk of the previous build is gone', () => {
    const { win, reload } = fakeWindow()
    installPreloadErrorReload(win, BUILD)

    const event = preloadError(win)

    expect(reload).toHaveBeenCalledTimes(1)
    expect(event.defaultPrevented).toBe(true)
  })

  it('swallows every further event of the same document while the reload runs', () => {
    // One page load fails for its CSS and for the module: one event each.
    const { win, reload } = fakeWindow()
    installPreloadErrorReload(win, BUILD)

    preloadError(win)
    const css = preloadError(win)
    const module = preloadError(win)

    expect(reload).toHaveBeenCalledTimes(1)
    expect(css.defaultPrevented).toBe(true)
    expect(module.defaultPrevented).toBe(true)
  })

  it('does not reload a second time for the same build', () => {
    const storage = new Map<string, string>()
    const first = fakeWindow(storage)
    installPreloadErrorReload(first.win, BUILD)
    preloadError(first.win)

    // The reloaded document runs the same build and installs the handler anew.
    const second = fakeWindow(storage)
    installPreloadErrorReload(second.win, BUILD)
    const event = preloadError(second.win)

    expect(second.reload).not.toHaveBeenCalled()
    expect(event.defaultPrevented).toBe(false)
  })

  it('reloads again after a later deploy changed the build', () => {
    const storage = new Map<string, string>()
    installPreloadErrorReload(fakeWindow(storage).win, BUILD)
    preloadError(fakeWindow(storage).win)

    const later = fakeWindow(storage)
    installPreloadErrorReload(later.win, 'https://opaa.example/assets/index-BBBB.js')
    preloadError(later.win)

    expect(later.reload).toHaveBeenCalledTimes(1)
  })

  it('does not reload when the session storage cannot remember the reload', () => {
    const { win, reload } = fakeWindow(new Map(), { storageFails: true })
    installPreloadErrorReload(win, BUILD)

    const event = preloadError(win)

    expect(reload).not.toHaveBeenCalled()
    expect(event.defaultPrevented).toBe(false)
  })

  it('stops listening once uninstalled', () => {
    const { win, reload } = fakeWindow()
    installPreloadErrorReload(win, BUILD)()

    preloadError(win)

    expect(reload).not.toHaveBeenCalled()
  })

  it('answers a chunk missing for good with exactly one reload, then the error boundary', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const storage = new Map<string, string>()

    const first = fakeWindow(storage)
    installPreloadErrorReload(first.win, BUILD)
    const view = renderPage(first.win)
    await vi.waitFor(() => expect(first.reload).toHaveBeenCalledTimes(1))
    expect(screen.getByRole('progressbar', { name: 'Seite wird geladen' })).toBeInTheDocument()
    view.unmount()

    const second = fakeWindow(storage)
    installPreloadErrorReload(second.win, BUILD)
    renderPage(second.win)

    expect(await screen.findByText('Etwas ist schiefgelaufen')).toBeInTheDocument()
    expect(second.reload).not.toHaveBeenCalled()
    vi.restoreAllMocks()
  })

  it('leaves an optional chunk to its fallback: the import is rejected and the page stays', async () => {
    const { win, reload } = fakeWindow()
    installPreloadErrorReload(win, BUILD)

    await expect(loadOptionalChunk(vitePreload(win, missingChunk))).rejects.toThrow(
      'Failed to fetch dynamically imported module',
    )

    expect(reload).not.toHaveBeenCalled()
    // the guard ends with the load: a page chunk that fails afterwards still reloads the page
    const event = preloadError(win)
    expect(event.defaultPrevented).toBe(true)
    expect(reload).toHaveBeenCalledTimes(1)
  })
})
