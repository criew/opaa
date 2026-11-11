import { vi } from 'vitest'

/**
 * Answers every media query as a viewport of the given width would. jsdom has no layout, so the
 * test environment renders the desktop form unless a test narrows it with this; restore
 * `window.matchMedia` afterwards.
 */
export function setViewportWidth(width: number) {
  window.matchMedia = ((query: string) => {
    const max = /max-width:\s*([\d.]+)px/.exec(query)
    const min = /min-width:\s*([\d.]+)px/.exec(query)
    return {
      matches: (!max || width <= Number(max[1])) && (!min || width >= Number(min[1])),
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }
  }) as typeof window.matchMedia
}
