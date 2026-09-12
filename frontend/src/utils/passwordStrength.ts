/**
 * The client-side estimate behind the strength meter and the generator of the self-service pages
 * (ADR-0033, Entscheidung 9). Orientation only: the backend's policy is what decides, and a
 * password this estimate calls "stark" can still be refused as too common. Deliberately no
 * complexity rules here either - the meter rewards length first, because that is what the policy
 * asks for.
 */

/** The policy's ceiling (ADR-0033, Entscheidung 9); BCrypt reads at most 72 bytes. */
export const PASSWORD_MAX_LENGTH = 64

export type PasswordStrengthScore = 0 | 1 | 2 | 3 | 4

export interface PasswordStrength {
  /** 0 for an empty entry, 1 (schwach) … 4 (stark). */
  score: PasswordStrengthScore
  label: string
}

const LABELS = ['', 'schwach', 'ausreichend', 'gut', 'stark'] as const

/** Where length alone starts to count for, and where it alone is enough for, the top ratings. */
const COMFORTABLE_LENGTH = 16
const GENEROUS_LENGTH = 20

/**
 * A length-led estimate: anything below the configured minimum reads "schwach" whatever it is made
 * of, and from there length is what lifts the rating - a mix of digits and special characters is
 * one way to the top rating, plain length the other. The policy asks for no complexity, so neither
 * may the meter beside it.
 */
export function passwordStrength(password: string, minLength: number): PasswordStrength {
  if (password.length === 0) return { score: 0, label: LABELS[0] }
  if (password.length < minLength) return { score: 1, label: LABELS[1] }
  let steps = 2
  if (password.length >= Math.max(minLength, COMFORTABLE_LENGTH)) steps++
  const mixed = /[0-9]/.test(password) && /[^A-Za-z0-9]/.test(password)
  if (mixed || password.length >= Math.max(minLength, GENEROUS_LENGTH)) steps++
  const score = Math.min(steps, 4) as PasswordStrengthScore
  return { score, label: LABELS[score] }
}

// Readable character classes: the ambiguous glyphs (0/O, 1/l/I) are left out so a generated
// password survives being written down and typed again.
const LOWER = 'abcdefghijkmnpqrstuvwxyz'
const UPPER = 'ABCDEFGHJKLMNPQRSTUVWXYZ'
const DIGITS = '23456789'
const SYMBOLS = '!@#$%^&*-_=+'
const ALL = LOWER + UPPER + DIGITS + SYMBOLS

/** The generated length: comfortably above any configurable minimum, well below the 64 ceiling. */
export const GENERATED_PASSWORD_LENGTH = 24

/**
 * An unbiased index in `[0, maxExclusive)` from the Web Crypto API with rejection sampling - no
 * `Math.random`, no modulo bias.
 */
function randomIndex(maxExclusive: number): number {
  const limit = Math.floor(0x1_0000_0000 / maxExclusive) * maxExclusive
  const buffer = new Uint32Array(1)
  let value: number
  do {
    crypto.getRandomValues(buffer)
    value = buffer[0]
  } while (value >= limit)
  return value % maxExclusive
}

function pick(chars: string): string {
  return chars[randomIndex(chars.length)]
}

/**
 * A password that meets the policy by construction: at least `minLength` characters (never more
 * than {@link PASSWORD_MAX_LENGTH}), one character of each class, nothing from a word list - so it
 * can be neither too short, too long nor too common, and it cannot equal an e-mail address.
 */
export function generateStrongPassword(
  minLength: number,
  length: number = GENERATED_PASSWORD_LENGTH,
): string {
  const required = [pick(LOWER), pick(UPPER), pick(DIGITS), pick(SYMBOLS)]
  const target = Math.min(PASSWORD_MAX_LENGTH, Math.max(length, minLength, required.length))
  const rest = Array.from({ length: target - required.length }, () => pick(ALL))
  const chars = [...required, ...rest]
  // Fisher-Yates, so the guaranteed characters are not always the first four.
  for (let i = chars.length - 1; i > 0; i--) {
    const j = randomIndex(i + 1)
    ;[chars[i], chars[j]] = [chars[j], chars[i]]
  }
  return chars.join('')
}
