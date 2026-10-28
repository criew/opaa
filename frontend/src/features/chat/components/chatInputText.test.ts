import { describe, expect, it } from 'vitest'
import { inputHint, inputPlaceholder } from './chatInputText'

describe('inputPlaceholder', () => {
  // Each hint group is held together by non-breaking spaces, so a wrap never splits "/ für".
  it('names both prefixes after the prompt while actions are on', () => {
    expect(inputPlaceholder(false, true, false)).toBe(
      'Nachricht eingeben … @\u00a0für\u00a0Quellen, /\u00a0für\u00a0Aktionen',
    )
  })

  it('names only "@" while no action source is active', () => {
    expect(inputPlaceholder(false, false, false)).toBe(
      'Nachricht eingeben … @\u00a0für\u00a0Quellen',
    )
  })

  it('names only "/" while an answer is pending', () => {
    expect(inputPlaceholder(true, true, false)).toBe(
      'Nachricht eingeben … /\u00a0für\u00a0Aktionen',
    )
    expect(inputPlaceholder(true, false, false)).toBe('Nachricht eingeben …')
  })

  // A narrow field (320 px screen, or a wide sidebar) has room for about twenty characters.
  it('falls back to the short hint in a narrow field', () => {
    expect(inputPlaceholder(false, true, true)).toBe('@\u00a0Quellen, /\u00a0Aktionen')
    expect(inputPlaceholder(false, false, true)).toBe('@\u00a0Quellen')
    expect(inputPlaceholder(true, true, true)).toBe('/\u00a0Aktionen')
    expect(inputPlaceholder(true, false, true)).toBe('Nachricht eingeben …')
  })
})

describe('inputHint', () => {
  it('is the description of the field, independent of the screen width', () => {
    expect(inputHint(false, true)).toBe('@ für Quellen, / für Aktionen')
    expect(inputHint(false, false)).toBe('@ für Quellen')
    expect(inputHint(true, true)).toBe('/ für Aktionen')
    expect(inputHint(true, false)).toBe('')
  })
})
