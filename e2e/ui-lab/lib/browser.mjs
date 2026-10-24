import { chromium } from '@playwright/test'

export const VIEWPORT_HEIGHT = 900

/**
 * Launches Chromium. `executablePath` (or UI_LAB_CHROMIUM) points at a browser installed outside
 * the Playwright cache, e.g. a pre-installed one whose build differs from the pinned Playwright.
 */
export function launch(executablePath = process.env.UI_LAB_CHROMIUM) {
  return chromium.launch(executablePath ? { executablePath } : {})
}

/**
 * A browser context that shows `scheme` from the very first paint: the emulated media query and
 * the stored theme choice are both set before any page loads. A page is never switched to another
 * scheme within its session; theme transitions caught mid-way produce false contrast findings.
 */
export async function schemeContext(browser, scheme, { width = 1280, recordVideo } = {}) {
  const context = await browser.newContext({
    viewport: { width, height: VIEWPORT_HEIGHT },
    locale: 'de-DE',
    colorScheme: scheme,
    reducedMotion: 'reduce',
    ...(recordVideo ? { recordVideo } : {}),
  })
  await context.addInitScript((mode) => {
    try {
      localStorage.setItem(
        'opaa-ui-preferences',
        JSON.stringify({ state: { themeMode: mode, sidebarWidth: 248 }, version: 0 }),
      )
    } catch {
      /* storage blocked: the emulated media query still selects the scheme */
    }
  }, scheme)
  return context
}

/**
 * The scheme the page actually shows, from the luminance of the body background the theme sets;
 * falls back to the media query while the body is transparent.
 */
export function appliedScheme(page) {
  return page
    .evaluate(() => {
      const parts = (getComputedStyle(document.body).backgroundColor.match(/[0-9.]+/g) || []).map(Number)
      if (parts.length >= 3 && (parts.length < 4 || parts[3] > 0)) {
        const luminance = (0.2126 * parts[0] + 0.7152 * parts[1] + 0.0722 * parts[2]) / 255
        return luminance < 0.5 ? 'dark' : 'light'
      }
      return matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
    })
    .catch(() => null)
}

export const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
