import { describe, expect, it } from 'vitest'
import {
  GENERATED_PASSWORD_LENGTH,
  PASSWORD_MAX_LENGTH,
  generateStrongPassword,
  passwordStrength,
} from './passwordStrength'

describe('passwordStrength', () => {
  it('says nothing about an empty entry', () => {
    expect(passwordStrength('', 12)).toEqual({ score: 0, label: '' })
  })

  // The meter is length-led: nothing below the configured minimum may read better than "schwach",
  // however many character classes it mixes.
  it('calls anything below the minimum length weak', () => {
    expect(passwordStrength('Ab3!', 12).label).toBe('schwach')
    expect(passwordStrength('Ab3!xY7#', 12).score).toBe(1)
  })

  it('rates a password above the minimum at least as sufficient', () => {
    expect(passwordStrength('abcdefghijkl', 12).score).toBeGreaterThanOrEqual(2)
  })

  it('rewards length and a mix of character classes with the top rating', () => {
    expect(passwordStrength('Sommerregen-42!x', 12)).toEqual({ score: 4, label: 'stark' })
  })

  // Length alone has to be able to reach the top rating - the policy asks for no complexity, and a
  // meter that demanded it would contradict the rule shown right next to it.
  it('reaches the top rating on length alone', () => {
    expect(passwordStrength('a'.repeat(24), 12).score).toBe(4)
  })
})

describe('generateStrongPassword', () => {
  it('produces the default length with one character of every class', () => {
    const password = generateStrongPassword(12)

    expect(password).toHaveLength(GENERATED_PASSWORD_LENGTH)
    expect(password).toMatch(/[a-z]/)
    expect(password).toMatch(/[A-Z]/)
    expect(password).toMatch(/[0-9]/)
    expect(password).toMatch(/[^A-Za-z0-9]/)
  })

  // "Sicheres Passwort erzeugen" has to clear the policy that is shown right above it - a generated
  // password a configured minimum of 40 would refuse would make the button a dead end.
  it('meets a minimum length above the default', () => {
    expect(generateStrongPassword(40)).toHaveLength(40)
    expect(passwordStrength(generateStrongPassword(40), 40).score).toBe(4)
  })

  it('never exceeds the policy maximum', () => {
    expect(generateStrongPassword(200).length).toBe(PASSWORD_MAX_LENGTH)
  })

  it('leaves out the glyphs that are easy to mistype', () => {
    for (let i = 0; i < 50; i++) {
      expect(generateStrongPassword(12)).not.toMatch(/[0O1lI]/)
    }
  })

  // Code points, like the backend's policy: an emoji is one character there, and a meter counting
  // UTF-16 units would call a password long enough that the backend refuses.
  it('counts characters, not UTF-16 units', () => {
    const twelveEmoji = '🙂'.repeat(12)

    expect(twelveEmoji.length).toBe(24)
    expect(passwordStrength(twelveEmoji, 12).score).toBeGreaterThanOrEqual(2)
    expect(passwordStrength('🙂'.repeat(11), 12).label).toBe('schwach')
  })

  it('does not repeat itself', () => {
    const seen = new Set(Array.from({ length: 20 }, () => generateStrongPassword(12)))
    expect(seen.size).toBe(20)
  })
})
