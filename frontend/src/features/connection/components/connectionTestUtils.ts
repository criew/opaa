import { screen } from '@testing-library/react'
import type userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, vi } from 'vitest'

/** jsdom has no matchMedia; the overview renders its table only on a desktop viewport. */
function desktopMatchMedia(query: string): MediaQueryList {
  return {
    matches: query.includes('min-width'),
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  } as unknown as MediaQueryList
}

/** Renders every test of the calling file on a desktop viewport. */
export function useDesktopViewport() {
  const original = window.matchMedia
  beforeEach(() => {
    window.matchMedia = desktopMatchMedia
  })
  afterEach(() => {
    window.matchMedia = original
  })
}

function escaped(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/** Opens the side panel of a profile from its row and returns the panel. */
export async function openProfile(
  user: ReturnType<typeof userEvent.setup>,
  name: string,
): Promise<HTMLElement> {
  await user.click(
    await screen.findByRole('button', {
      name: new RegExp(`^(Details|Beheben): ${escaped(name)}$`),
    }),
  )
  return screen.findByRole('dialog', { name })
}
