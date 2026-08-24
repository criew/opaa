import { describe, expect, test } from 'vitest'
import { createAppTheme, createSidebarTheme } from './theme'
import {
  blue,
  carbon,
  darkRoles,
  gray,
  lightRoles,
  navy,
  navyRoles,
  radius,
  railRoles,
  semanticColors,
  white,
} from './tokens'
import { contrastRatio, TEXT_CONTRAST_MINIMUM } from '../utils/contrast'

describe('tokens', () => {
  test('light and dark schemes define the identical set of roles', () => {
    expect(Object.keys(darkRoles).sort()).toEqual(Object.keys(lightRoles).sort())
  })

  test('accent states carry the sampled mockup values (hover -8%, press -16%)', () => {
    expect(lightRoles.accent).toBe(blue[500])
    expect(lightRoles.accentHover).toBe(blue[600])
    expect(lightRoles.accentPress).toBe(blue[700])
  })

  test('the default radius is the 10px squircle from the guidelines', () => {
    expect(radius.md).toBe(10)
  })

  // #725: the Wissensbibliotheken table's column head ("Stand") and secondary description text
  // both render in fg-3, which axe-core flagged at 3.68:1 against white once the E2E suite
  // started seeding a real row (test(e2e) #233 - before that the table was always empty and the
  // violation never rendered). fg-3 is gray[400] with 3.69/3.47/3.28:1 against bg1/bg2/bg3
  // respectively - all below the 4.5:1 WCAG AA floor; reassigning it to gray[500] fixes every
  // surface that uses the role, not just this one table.
  test('fg-3 meets the 4.5:1 text contrast floor against every light-scheme surface (#725)', () => {
    const backgrounds: Array<[string, string]> = [
      ['bg1', lightRoles.bg1],
      ['bg2', lightRoles.bg2],
      ['bg3', lightRoles.bg3],
    ]

    for (const [label, background] of backgrounds) {
      const ratio = contrastRatio(lightRoles.fg3, background)
      expect(ratio, `fg-3 on ${label}`).not.toBeNull()
      expect(ratio, `fg-3 on ${label}`).toBeGreaterThanOrEqual(TEXT_CONTRAST_MINIMUM)
    }
  })

  // #853: navyRoles.fg3/railRoles.fg3 ('#7A8BA0') only reach 4.5:1 against bg1 (4.65:1 navy,
  // 5.26:1 rail); against bg2 they drop to 3.80:1 and against bg3 to 2.99:1 - both below the
  // WCAG AA floor. Verified against Sidebar.tsx and GlobalRail.tsx (the only two consumers of
  // createSidebarTheme/createRailTheme): fg-3 there only ever renders on bg1 - the hover fill
  // (bg2) and the active tile (bg3) both switch their *text* to fg-1 (white) instead, per
  // guidelines.md 2.3 ("die Textfarbe wechselt auf Weiß"). This guard therefore checks fg-3
  // against the surface it is actually used on; it must fail loudly the moment a future change
  // starts rendering fg-3 text on bg2/bg3 of either role set, since neither reaches 4.5:1 there.
  test('fg-3 in the navy and rail role sets meets 4.5:1 against the surface it renders text on (#853)', () => {
    for (const [label, roles] of [
      ['navy', navyRoles],
      ['rail', railRoles],
    ] as const) {
      const ratio = contrastRatio(roles.fg3, roles.bg1)
      expect(ratio, `${label} fg-3 on bg1`).not.toBeNull()
      expect(ratio, `${label} fg-3 on bg1`).toBeGreaterThanOrEqual(TEXT_CONTRAST_MINIMUM)
    }
  })
})

describe('createAppTheme', () => {
  test('light scheme maps the semantic roles onto the MUI palette', () => {
    const theme = createAppTheme('light')

    expect(theme.palette.background.default).toBe(white)
    expect(theme.palette.text.primary).toBe(navy[800])
    expect(theme.palette.text.secondary).toBe(gray[600])
    expect(theme.palette.primary.main).toBe(blue[500])
    expect(theme.palette.divider).toBe(lightRoles.border)
    expect(theme.palette.error.main).toBe(semanticColors.danger)
  })

  test('dark scheme maps the neutral carbon roles onto the MUI palette (#654)', () => {
    const theme = createAppTheme('dark')

    expect(theme.palette.background.default).toBe(carbon[950])
    expect(theme.palette.text.primary).toBe(darkRoles.fg1)
    expect(theme.palette.text.secondary).toBe(darkRoles.fg2)
    expect(theme.palette.primary.main).toBe(blue[500])
    expect(theme.palette.divider).toBe(darkRoles.border)
  })

  test('the navy roles keep the mockup sidebar values for the light scheme (#654)', () => {
    expect(navyRoles.bg1).toBe(navy[800])
    expect(navyRoles.fg1).toBe(white)
    expect(Object.keys(navyRoles).sort()).toEqual(Object.keys(darkRoles).sort())
  })

  test('shape and typography come from the token layer', () => {
    const theme = createAppTheme('light')

    expect(theme.shape.borderRadius).toBe(radius.md)
    expect(theme.typography.fontFamily).toContain('Inter')
    expect(theme.typography.button.textTransform).toBe('none')
    expect(theme.typography.button.fontWeight).toBe(500)
  })

  test('a branding primary color replaces the accent and derives hover/press states', () => {
    const theme = createAppTheme('light', { primaryColor: '#7A1FA2' })

    expect(theme.palette.primary.main).toBe('#7A1FA2')
    // Derived, not hardwired to the blue scale: darker than the configured color.
    expect(theme.palette.primary.dark).not.toBe(blue[700])
    expect(theme.palette.primary.dark).not.toBe('#7A1FA2')
  })

  test('without branding the derived button states stay pixel-identical to the mockups', () => {
    const theme = createAppTheme('light')

    expect(theme.palette.primary.dark).toBe(blue[700])
  })

  test('the sidebar theme stays navy while the app is light (#654, mockup 1a)', () => {
    const theme = createSidebarTheme('light')

    expect(theme.palette.mode).toBe('dark')
    expect(theme.palette.background.default).toBe(navy[800])
  })

  test('the sidebar theme follows the carbon dark scheme while the app is dark (#654)', () => {
    const theme = createSidebarTheme('dark')

    expect(theme.palette.background.default).toBe(carbon[950])
  })

  test('the sidebar theme derives its accent from a branding color like the app theme', () => {
    const theme = createSidebarTheme('light', { primaryColor: '#7A1FA2' })

    expect(theme.palette.primary.main).toBe('#7A1FA2')
  })

  test('an empty branding object behaves exactly like no branding', () => {
    const branded = createAppTheme('light', {})
    const plain = createAppTheme('light')

    expect(branded.palette.primary.main).toBe(plain.palette.primary.main)
    expect(branded.palette.primary.dark).toBe(plain.palette.primary.dark)
  })

  test('ButtonBase re-applies the focus ring that MUI resets with outline: 0', () => {
    const theme = createAppTheme('light')
    const root = theme.components?.MuiButtonBase?.styleOverrides?.root as Record<string, unknown>
    const ring = root['&:focus-visible, &.Mui-focusVisible'] as Record<string, string>

    expect(ring.outline).toMatch(/^3px solid /)
    expect(ring.outlineOffset).toBe('2px')
  })

  test('the deDE MUI locale replaces English component defaults (#784)', () => {
    const theme = createAppTheme('light')
    const autocompleteDefaults = theme.components?.MuiAutocomplete?.defaultProps as Record<
      string,
      string
    >

    // Without the locale, MUI falls back to English ("No options", "Loading…", …); every
    // Autocomplete in the app that doesn't set its own noOptionsText would show that default.
    // The exact German wording isn't pinned here (it's MUI's, not ours, and could rephrase
    // without a real defect) - the DOM-level tests are the actual guard for user-facing text.
    expect(autocompleteDefaults.noOptionsText).toBeDefined()
    expect(autocompleteDefaults.noOptionsText).not.toBe('No options')
    expect(autocompleteDefaults.loadingText).toBeDefined()
    expect(autocompleteDefaults.loadingText).not.toBe('Loading…')
    expect(autocompleteDefaults.clearText).toBeDefined()
    expect(autocompleteDefaults.clearText).not.toBe('Clear')
    expect(autocompleteDefaults.openText).toBeDefined()
    expect(autocompleteDefaults.openText).not.toBe('Open')
    expect(autocompleteDefaults.closeText).toBeDefined()
    expect(autocompleteDefaults.closeText).not.toBe('Close')
  })

  test('reduced motion also disables smooth scrolling', () => {
    const theme = createAppTheme('dark')
    const baseline = theme.components?.MuiCssBaseline?.styleOverrides as Record<string, unknown>
    const reduced = baseline['@media (prefers-reduced-motion: reduce)'] as Record<
      string,
      Record<string, string>
    >

    expect(reduced['*, *::before, *::after'].scrollBehavior).toBe('auto !important')
  })
})
