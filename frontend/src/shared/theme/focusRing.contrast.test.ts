import { describe, expect, it } from 'vitest'
import type { Theme } from '@mui/material/styles'
import { alpha, darken, decomposeColor, getContrastRatio, lighten } from '@mui/material/styles'
import { createAppTheme, createRailTheme } from './theme'
import { blue, darkRoles, lightRoles, railRoles } from './tokens'
import type { SchemeRoles } from './tokens'
import { TEXT_CONTRAST_MINIMUM, UI_CONTRAST_MINIMUM } from '../utils/contrast'

/**
 * The focus indicator is a graphical object (WCAG 1.4.11, accessibility.md 2.4): it needs 3:1
 * against every colour it borders. These are the surfaces focus occurs on, per role set; a
 * translucent ring or tint is composited over its ground first, as the browser paints it.
 */

/** Guidelines 4.4: the ring is at least 2px wide and stands 2px off accent surfaces. */
const MIN_RING_WIDTH_PX = 2
const MIN_RING_OFFSET_PX = 2
const FIELD_OUTLINE_WIDTH_PX = 2

interface Ring {
  width: number
  color: string
  offset: number
}

/** Composites a possibly translucent colour over an opaque ground. */
function flatten(color: string, ground: string): string {
  const fg = decomposeColor(color).values
  const bg = decomposeColor(ground).values
  const a = fg[3] ?? 1
  const mix = (i: number) => Math.round(fg[i] * a + bg[i] * (1 - a))
  return `rgb(${mix(0)}, ${mix(1)}, ${mix(2)})`
}

function parseOutline(outline: unknown): { width: number; color: string } {
  const match = /^(\d+(?:\.\d+)?)px solid (.+)$/.exec(String(outline))
  expect(match, `outline "${String(outline)}" is not "<n>px solid <colour>"`).not.toBeNull()
  return { width: Number(match?.[1]), color: String(match?.[2]) }
}

/** The global ring every focusable element gets (`*:focus-visible`). */
function globalRing(theme: Theme): Ring {
  const baseline = theme.components?.MuiCssBaseline?.styleOverrides as Record<string, unknown>
  const rule = baseline['*:focus-visible'] as Record<string, unknown>
  return { ...parseOutline(rule.outline), offset: Number.parseFloat(String(rule.outlineOffset)) }
}

function outlinedInputRule(theme: Theme, selector: string): Record<string, unknown> {
  const root = theme.components?.MuiOutlinedInput?.styleOverrides?.root as Record<string, unknown>
  return (root[selector] ?? {}) as Record<string, unknown>
}

/**
 * Surfaces a focused element can sit on or carry itself (an inset ring borders the item's fill).
 * The rail only knows its three grounds - its tiles fill with bg-2/bg-3, and its menus render in
 * the app theme - so the selection and alert tints are app surfaces only.
 */
function surfaces(theme: Theme, roles: SchemeRoles, appSurfaces: boolean): Array<[string, string]> {
  const accent = theme.palette.primary.main
  const { selectedOpacity, hoverOpacity } = theme.palette.action
  const tinted: Array<[string, string]> = [
    ['selected tint', alpha(accent, selectedOpacity)],
    ['selected + hover tint', alpha(accent, selectedOpacity + hoverOpacity)],
    ['accent tint 16 %', alpha(accent, 0.16)],
    ['hover', theme.palette.action.hover],
    ['menu selection', theme.palette.mode === 'dark' ? alpha(accent, 0.16) : blue[50]],
  ]
  const isDark = theme.palette.mode === 'dark'
  const alertGrounds: Array<[string, string]> = (
    ['error', 'warning', 'success', 'info'] as const
  ).map((tone) => [
    `alert ${tone}`,
    isDark ? darken(theme.palette[tone].light, 0.9) : lighten(theme.palette[tone].light, 0.9),
  ])
  const grounds: Array<[string, string]> = [
    ['bg-1', roles.bg1],
    ['bg-2', roles.bg2],
    ['bg-3', roles.bg3],
  ]
  if (!appSurfaces) return grounds
  return [
    ...grounds,
    ...grounds.flatMap(([groundName, ground]) =>
      tinted.map(([name, tint]): [string, string] => [
        `${name} on ${groundName}`,
        flatten(tint, ground),
      ]),
    ),
    ...alertGrounds,
  ]
}

const SETS: Array<[string, () => Theme, SchemeRoles, boolean]> = [
  ['light', () => createAppTheme('light'), lightRoles, true],
  ['dark', () => createAppTheme('dark'), darkRoles, true],
  ['rail (light app)', () => createRailTheme('light'), railRoles, false],
  [
    'light, house colour',
    () => createAppTheme('light', { primaryColor: '#1153EE' }),
    lightRoles,
    true,
  ],
  [
    'dark, house colour',
    () => createAppTheme('dark', { primaryColor: '#1153EE' }),
    darkRoles,
    true,
  ],
  [
    'rail (light app), house colour',
    () => createRailTheme('light', { primaryColor: '#1153EE' }),
    railRoles,
    false,
  ],
]

describe.each(SETS)('focus ring (%s)', (_label, build, roles, appSurfaces) => {
  it('is an opaque line of at least 2px', () => {
    const ring = globalRing(build())
    expect(decomposeColor(ring.color).values[3] ?? 1, `ring colour ${ring.color}`).toBe(1)
    expect(ring.width).toBeGreaterThanOrEqual(MIN_RING_WIDTH_PX)
  })

  it('reaches 3:1 against every surface focus occurs on', () => {
    const theme = build()
    const ring = globalRing(theme)
    for (const [name, surface] of surfaces(theme, roles, appSurfaces)) {
      const painted = flatten(ring.color, surface)
      expect(getContrastRatio(painted, surface), `ring on ${name}`).toBeGreaterThanOrEqual(
        UI_CONTRAST_MINIMUM,
      )
    }
  })

  it('stands off accent surfaces, so it borders the ground instead of the filled action', () => {
    const theme = build()
    const ring = globalRing(theme)
    // On a filled primary button the accent ring would meet its own colour; the gap in between
    // shows the ground the button stands on, against which the test above holds.
    expect(ring.offset).toBeGreaterThanOrEqual(MIN_RING_OFFSET_PX)
  })

  it('is the ring MUI components draw as well', () => {
    const theme = build()
    const ring = globalRing(theme)
    expect(theme.focusVisible, 'theme.focusVisible').toBeTruthy()
    const muiRing = theme.focusVisible as Record<string, unknown>
    expect(muiRing.outlineColor).toBe(ring.color)
    expect(muiRing.outlineWidth).toBe(ring.width)
    expect(muiRing.outlineStyle).toBe('solid')
  })

  it('keeps the gap where MUI components draw the ring', () => {
    // MUI multiplies the offset by a private sign variable to inset the ring in clipping
    // containers; outside them the factor is 1, so the authored px value is the gap.
    const offset = String((build().focusVisible as Record<string, unknown>).outlineOffset)
    const match = /\*\s*(-?\d+(?:\.\d+)?)px\)$/.exec(offset)
    expect(match, `outlineOffset "${offset}"`).not.toBeNull()
    expect(Number(match?.[1])).toBeGreaterThanOrEqual(MIN_RING_OFFSET_PX)
  })
})

describe.each([
  ['light', 'light', lightRoles],
  ['dark', 'dark', darkRoles],
] as const)('focused outlined field (%s)', (_label, mode, roles) => {
  // A ring around the field would run through its floating label; the field's own outline,
  // widened to 2px, is the focus indicator instead (guidelines 4.4 and 5.2).
  it.each([
    ['without an error', '&.Mui-focused', roles.accent],
    ['in error', '&.Mui-error.Mui-focused', roles.danger],
  ])('shows focus %s as a 2px outline at 4.5:1, without a ring', (_case, state, colour) => {
    const theme = createAppTheme(mode)
    expect(outlinedInputRule(theme, state).outline, 'ring around the field').toBeUndefined()
    const outline = outlinedInputRule(theme, `${state} .MuiOutlinedInput-notchedOutline`)
    expect(outline.borderColor).toBe(colour)
    expect(outline.borderWidth).toBe(FIELD_OUTLINE_WIDTH_PX)
    for (const ground of [roles.bg1, roles.bg2, roles.bg3]) {
      expect(getContrastRatio(colour, ground), `outline on ${ground}`).toBeGreaterThanOrEqual(
        TEXT_CONTRAST_MINIMUM,
      )
    }
  })
})
