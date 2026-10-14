import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { installPreloadErrorReload, RELOAD_GUARD_MS } from './preloadErrorReload'

/** A window stand-in: real event dispatch and session storage, a spy for reload. */
function fakeWindow() {
  const target = new EventTarget()
  const storage = new Map<string, string>()
  const reload = vi.fn()
  const win = Object.assign(target, {
    sessionStorage: {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => void storage.set(key, value),
    },
    location: { reload },
  }) as unknown as Window
  return { win, reload }
}

function preloadError(win: Window) {
  const event = new Event('vite:preloadError', { cancelable: true })
  win.dispatchEvent(event)
  return event
}

describe('installPreloadErrorReload', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-14T10:00:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('reloads once and swallows the error when a chunk of the previous build is gone', () => {
    const { win, reload } = fakeWindow()
    installPreloadErrorReload(win)

    const event = preloadError(win)

    expect(reload).toHaveBeenCalledTimes(1)
    expect(event.defaultPrevented).toBe(true)
  })

  it('does not reload again when loading fails right after the reload', () => {
    const { win, reload } = fakeWindow()
    installPreloadErrorReload(win)
    preloadError(win)

    // The reloaded page installs the handler anew; session storage survives the reload.
    vi.advanceTimersByTime(RELOAD_GUARD_MS - 1)
    const second = preloadError(win)

    expect(reload).toHaveBeenCalledTimes(1)
    expect(second.defaultPrevented).toBe(false)
  })

  it('reloads again for a later deploy once the guard window has passed', () => {
    const { win, reload } = fakeWindow()
    installPreloadErrorReload(win)
    preloadError(win)

    vi.advanceTimersByTime(RELOAD_GUARD_MS)
    preloadError(win)

    expect(reload).toHaveBeenCalledTimes(2)
  })

  it('stops listening once uninstalled', () => {
    const { win, reload } = fakeWindow()
    const uninstall = installPreloadErrorReload(win)
    uninstall()

    preloadError(win)

    expect(reload).not.toHaveBeenCalled()
  })
})
