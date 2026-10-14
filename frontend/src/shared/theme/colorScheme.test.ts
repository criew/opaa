import { describe, expect, it } from 'vitest'
import { colorSchemeToThemeMode, resolveThemeMode } from './colorScheme'

describe('colorSchemeToThemeMode', () => {
  it('maps the API vocabulary onto the interface one', () => {
    expect(colorSchemeToThemeMode('LIGHT')).toBe('light')
    expect(colorSchemeToThemeMode('DARK')).toBe('dark')
    expect(colorSchemeToThemeMode('SYSTEM')).toBe('system')
  })
})

describe('resolveThemeMode', () => {
  it('applies the operator default while the user has made no choice', () => {
    expect(resolveThemeMode(null, 'DARK')).toBe('dark')
    expect(resolveThemeMode(null, 'LIGHT')).toBe('light')
    expect(resolveThemeMode(null, 'SYSTEM')).toBe('system')
  })

  /**
   * The guarantee the whole `ThemeMode | null` distinction exists for (#583): an operator changing
   * the deployment default must not flip the interface out from under someone who deliberately
   * picked light or dark - including the person who deliberately picked "system".
   */
  it('never lets the operator default override an own choice', () => {
    expect(resolveThemeMode('light', 'DARK')).toBe('light')
    expect(resolveThemeMode('dark', 'LIGHT')).toBe('dark')
    expect(resolveThemeMode('system', 'DARK')).toBe('system')
  })
})
