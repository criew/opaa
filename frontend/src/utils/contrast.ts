/**
 * WCAG 2.1 contrast arithmetic for the branding form (#583). An operator picking their house's
 * accent colour has no way of knowing whether white button text stays legible on it, or whether a
 * focus ring is still visible against the dark scheme - this computes the answer and lets the form
 * say so.
 *
 * Deliberately a warning, never a block (#583: "blockiert aber nicht"): a Behörde's corporate
 * colour is not something this application gets to veto. What it can do is make the consequence
 * visible before the choice is saved.
 */

import { darkRoles, lightRoles } from '../theme/tokens'

/** WCAG 2.1 SC 1.4.3, normal text. */
export const TEXT_CONTRAST_MINIMUM = 4.5

/** WCAG 2.1 SC 1.4.11, user-interface components and graphical objects. */
export const UI_CONTRAST_MINIMUM = 3

/** Parses `#RRGGBB` (case-insensitive) into 0..255 channels; null for anything else. */
export function parseHexColor(color: string): [number, number, number] | null {
  const match = /^#([0-9a-f]{6})$/i.exec(color.trim())
  if (!match) return null
  const value = Number.parseInt(match[1], 16)
  return [(value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff]
}

/**
 * Relative luminance per WCAG 2.1. The 0.03928 threshold and the 2.4 exponent are the
 * specification's own values, not an approximation - keeping them literal makes the function
 * checkable against the spec rather than against someone's memory of it.
 */
function relativeLuminance([r, g, b]: [number, number, number]): number {
  const channel = (value: number) => {
    const v = value / 255
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4
  }
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
}

/**
 * Contrast ratio between two `#RRGGBB` colours, 1..21. Returns null if either colour is not a
 * six-digit hex value - the caller then has nothing to warn about, because there is no colour yet.
 */
export function contrastRatio(foreground: string, background: string): number | null {
  const fg = parseHexColor(foreground)
  const bg = parseHexColor(background)
  if (!fg || !bg) return null
  const lighter = Math.max(relativeLuminance(fg), relativeLuminance(bg))
  const darker = Math.min(relativeLuminance(fg), relativeLuminance(bg))
  return (lighter + 0.05) / (darker + 0.05)
}

/**
 * Darkens a `#RRGGBB` colour like MUI's `darken` (channels scaled toward black), staying in hex
 * so the result feeds back into {@link contrastRatio}. Null for anything unparseable.
 */
export function darkenHex(color: string, coefficient: number): string | null {
  const channels = parseHexColor(color)
  if (!channels) return null
  const scaled = channels.map((value) => Math.round(value * (1 - coefficient)))
  return `#${scaled.map((value) => value.toString(16).padStart(2, '0')).join('')}`
}

/** One darkening step of the surface derivation - matches the sampled -8% hover rhythm. */
const ACCENT_SURFACE_DARKEN_STEP = 0.08

/**
 * Bounded on purpose (#634): a corporate colour is not this application's to repaint. Six steps
 * rescue a moderately light accent; an extreme one (pale yellow) stays failing and triggers the
 * contrast warning instead of being darkened beyond recognition.
 */
const ACCENT_SURFACE_MAX_STEPS = 6

/**
 * The filled-action surface for a configured accent colour (#634): the colour itself when white
 * text already reaches 4.5:1 on it, otherwise darkened in -8% steps until it does - at most
 * {@link ACCENT_SURFACE_MAX_STEPS} steps, after which the last attempt is returned even when it
 * still fails (see {@link checkAccentContrast}, which then warns). Unparseable input is returned
 * unchanged - there is nothing to derive from.
 */
export function deriveAccentSurface(accent: string): string {
  let candidate = accent
  for (let step = 0; step <= ACCENT_SURFACE_MAX_STEPS; step++) {
    const ratio = contrastRatio('#FFFFFF', candidate)
    if (ratio === null || ratio >= TEXT_CONTRAST_MINIMUM) {
      return candidate
    }
    if (step === ACCENT_SURFACE_MAX_STEPS) {
      break
    }
    const darker = darkenHex(candidate, ACCENT_SURFACE_DARKEN_STEP)
    if (!darker) return candidate
    candidate = darker
  }
  return candidate
}

export interface ContrastCheck {
  /** German label of what was compared, for the warning text. */
  label: string
  ratio: number
  required: number
  passes: boolean
}

/**
 * The three checks that decide whether an accent colour is usable in this design system, both
 * schemes included (guidelines: "Beide Schemata sind gleichermaßen verbindlich"):
 *
 * 1. Button label on the accent surface - `accentFg` is white in both schemes. Since #634 the
 *    filled surface is {@link deriveAccentSurface}'s bounded darkening of the configured colour,
 *    so this check evaluates what will actually render; it only fails when even the darkened
 *    surface cannot carry white text. Text threshold.
 * 2. and 3. The accent against the base surface of each scheme - this is what a focus ring, a
 *    selected border or an icon has to stand out from. UI-component threshold.
 *
 * An accent can easily pass one scheme and fail the other, which is exactly why both are listed
 * separately rather than reduced to a single verdict.
 */
export function checkAccentContrast(accent: string): ContrastCheck[] {
  const checks: Array<{ label: string; foreground: string; background: string; required: number }> =
    [
      {
        label: 'Beschriftung auf Schaltflächen',
        foreground: lightRoles.accentFg,
        background: deriveAccentSurface(accent),
        required: TEXT_CONTRAST_MINIMUM,
      },
      {
        label: 'Akzentfarbe auf hellem Hintergrund',
        foreground: accent,
        background: lightRoles.bg1,
        required: UI_CONTRAST_MINIMUM,
      },
      {
        label: 'Akzentfarbe auf dunklem Hintergrund',
        foreground: accent,
        background: darkRoles.bg1,
        required: UI_CONTRAST_MINIMUM,
      },
    ]

  return checks.flatMap(({ label, foreground, background, required }) => {
    const ratio = contrastRatio(foreground, background)
    if (ratio === null) return []
    return [{ label, ratio, required, passes: ratio >= required }]
  })
}

/** Formats a ratio the way WCAG tooling conventionally writes it: "4,5:1", German decimal comma. */
export function formatContrastRatio(ratio: number): string {
  return `${ratio.toFixed(1).replace('.', ',')}:1`
}
