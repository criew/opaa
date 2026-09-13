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

/**
 * Lightens a `#RRGGBB` colour like MUI's `lighten` (channels scaled toward white), staying in hex
 * so the result feeds back into {@link contrastRatio}. Null for anything unparseable.
 */
export function lightenHex(color: string, coefficient: number): string | null {
  const channels = parseHexColor(color)
  if (!channels) return null
  const scaled = channels.map((value) => Math.round(value + (255 - value) * coefficient))
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

/** One step of the text derivation - the same 8% rhythm the surface derivation uses. */
const ACCENT_TEXT_STEP = 0.08

/**
 * Bounded like {@link ACCENT_SURFACE_MAX_STEPS}, and for the same reason: an accent that needs
 * more than six steps is no longer the colour the operator chose. It is returned as far as it got,
 * and {@link checkAccentContrast} says so in the branding form.
 */
const ACCENT_TEXT_MAX_STEPS = 6

/**
 * The accent colour **as text** for one scheme (#1600): the colour itself when it already reaches
 * 4.5:1 against every ground of that scheme, otherwise moved away from them in 8% steps until it
 * does — lightened on a dark ground, darkened on a light one.
 *
 * This is the counterpart to {@link deriveAccentSurface}, which solved the same problem for the
 * filled surface in #634. Without it a house colour that is legible in one scheme is not in the
 * other: the demo's `#1153EE` reaches 6,0:1 on white but only 3,3:1 on the dark ground, and every
 * link, „Passwort vergessen?" and active tab rendered in it fell below the threshold — in the
 * whole application, not just on the sign-in pages.
 *
 * The **surface** stays untouched: filled buttons keep carrying `accentSurface`, so the brand
 * remains itself wherever it appears as an area. Only where the colour has to be read as text does
 * it move, and only as far as the threshold demands.
 */
export function deriveAccentText(accent: string, backgrounds: readonly string[]): string {
  const grounds = backgrounds.map(parseHexColor)
  if (grounds.some((ground) => ground === null) || grounds.length === 0) return accent
  // Geprüft wird gegen **jede** Grundfläche des Schemas, nicht nur gegen die der Seite: Ein Verweis
  // steht genauso in einem Dialog (bg-2) oder in einem Tabellenkopf (bg-3), und das Rollenmodell
  // sagt die Schwelle für bg-1..3 zu. Die Richtung gibt die erste Fläche vor - innerhalb eines
  // Schemas liegen alle drei auf derselben Seite der Helligkeitsmitte.
  const groundIsDark = relativeLuminance(grounds[0] as [number, number, number]) < 0.5
  const schritt = groundIsDark ? lightenHex : darkenHex

  const reichtAus = (farbe: string) =>
    backgrounds.every((ground) => {
      const ratio = contrastRatio(farbe, ground)
      return ratio === null || ratio >= TEXT_CONTRAST_MINIMUM
    })

  let candidate = accent
  for (let step = 0; step <= ACCENT_TEXT_MAX_STEPS; step++) {
    if (reichtAus(candidate)) return candidate
    if (step === ACCENT_TEXT_MAX_STEPS) break
    const next = schritt(candidate, ACCENT_TEXT_STEP)
    if (!next) return candidate
    candidate = next
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
 * 2. and 3. The accent **as text** in each scheme, against the least favourable surface of that
 *    scheme. Since #1600 the application moves the colour away from the ground until it reaches
 *    the threshold, so these two check what will actually render - they only fail when even the
 *    bounded derivation cannot get there, and then the operator is told rather than silently
 *    served unreadable links. Text threshold, which also covers the 3:1 a focus ring or a border
 *    needs.
 *
 * An accent can easily pass one scheme and fail the other, which is exactly why both are listed
 * separately rather than reduced to a single verdict.
 */
export function checkAccentContrast(accent: string): ContrastCheck[] {
  const hellerGrund = [lightRoles.bg1, lightRoles.bg2, lightRoles.bg3]
  const dunklerGrund = [darkRoles.bg1, darkRoles.bg2, darkRoles.bg3]
  const checks: Array<{ label: string; foreground: string; background: string; required: number }> =
    [
      {
        label: 'Beschriftung auf Schaltflächen',
        foreground: lightRoles.accentFg,
        background: deriveAccentSurface(accent),
        required: TEXT_CONTRAST_MINIMUM,
      },
      {
        label: 'Akzentfarbe als Text im hellen Schema',
        foreground: deriveAccentText(accent, hellerGrund),
        background: lightRoles.bg3,
        required: TEXT_CONTRAST_MINIMUM,
      },
      {
        label: 'Akzentfarbe als Text im dunklen Schema',
        foreground: deriveAccentText(accent, dunklerGrund),
        background: darkRoles.bg3,
        required: TEXT_CONTRAST_MINIMUM,
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
