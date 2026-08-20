import { describe, expect, test } from 'vitest'
import { createAppTheme } from './theme'
import { blue, darkRoles, gray, lightRoles, navy, radius, semanticColors, white } from './tokens'

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

  test('dark scheme maps the semantic roles onto the MUI palette', () => {
    const theme = createAppTheme('dark')

    expect(theme.palette.background.default).toBe(navy[800])
    expect(theme.palette.text.primary).toBe(white)
    expect(theme.palette.text.secondary).toBe(darkRoles.fg2)
    expect(theme.palette.primary.main).toBe(blue[500])
    expect(theme.palette.divider).toBe(darkRoles.border)
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

  test('an empty branding object behaves exactly like no branding', () => {
    const branded = createAppTheme('light', {})
    const plain = createAppTheme('light')

    expect(branded.palette.primary.main).toBe(plain.palette.primary.main)
    expect(branded.palette.primary.dark).toBe(plain.palette.primary.dark)
  })
})
