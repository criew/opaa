import { describe, expect, it } from 'vitest'
import { inputHint, inputPlaceholder } from './chatInputText'

describe('inputPlaceholder', () => {
  it('names both prefixes after the prompt while actions are on', () => {
    expect(inputPlaceholder(false, true, false)).toBe(
      'Nachricht eingeben … @ für Quellen, / für Aktionen',
    )
  })

  it('names only "@" while no action source is active', () => {
    expect(inputPlaceholder(false, false, false)).toBe('Nachricht eingeben … @ für Quellen')
  })

  it('names only "/" while an answer is pending', () => {
    expect(inputPlaceholder(true, true, false)).toBe('Nachricht eingeben … / für Aktionen')
    expect(inputPlaceholder(true, false, false)).toBe('Nachricht eingeben …')
  })

  // A narrow screen (down to 320 px) has room for about twenty characters in the field.
  it('falls back to the short hint on a narrow screen', () => {
    expect(inputPlaceholder(false, true, true)).toBe('@ Quellen, / Aktionen')
    expect(inputPlaceholder(false, false, true)).toBe('@ Quellen')
    expect(inputPlaceholder(true, true, true)).toBe('/ Aktionen')
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
