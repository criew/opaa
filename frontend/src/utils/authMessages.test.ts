import { describe, expect, it } from 'vitest'
import { fieldErrorMessages, passwordPolicyText, tooManyRequestsMessage } from './authMessages'
import { PASSWORD_MAX_BYTES, PASSWORD_MAX_LENGTH } from './passwordStrength'

describe('fieldErrorMessages', () => {
  it('puts one sentence per rendered field, in that field', () => {
    const { byField, unassigned } = fieldErrorMessages(
      [
        { field: 'email', code: 'INVALID_ADDRESS', message: 'x' },
        { field: 'displayName', code: 'REQUIRED', message: 'x' },
      ],
      12,
      ['email', 'displayName'],
    )

    expect(byField.email).toBe('Bitte geben Sie eine gültige E-Mail-Adresse an.')
    expect(byField.displayName).toBe('Bitte geben Sie Ihren Namen an.')
    expect(unassigned).toEqual([])
  })

  // The policy reports every violated rule of one field at once, so the field names all of them.
  it('joins several codes of one field into one sentence', () => {
    const { byField } = fieldErrorMessages(
      [
        { field: 'newPassword', code: 'TOO_SHORT', message: 'x' },
        { field: 'newPassword', code: 'TOO_COMMON', message: 'x' },
      ],
      12,
      ['newPassword'],
    )

    expect(byField.newPassword).toContain('mindestens 12 Zeichen')
    expect(byField.newPassword).toContain('zu häufig')
  })

  /**
   * The regression this split exists for: a refusal naming a field the form does not render would
   * otherwise be dropped, and the form would look as if nothing had happened.
   */
  it('hands back the sentences of fields the form does not render', () => {
    const { byField, unassigned } = fieldErrorMessages(
      [{ field: 'expiresAt', code: 'REQUIRED', message: 'Ein Ablaufdatum ist Pflicht.' }],
      12,
      ['email'],
    )

    expect(byField).toEqual({})
    expect(unassigned).toEqual(['Ein Ablaufdatum ist Pflicht.'])
  })

  // An unknown field must not borrow the password wording - "Das Passwort erfüllt die Vorgaben
  // nicht." about a field that is no password is worse than no sentence.
  it('uses the backend wording for a field it does not know', () => {
    const { unassigned } = fieldErrorMessages(
      [{ field: 'somethingNew', code: 'WEIRD', message: 'Dieser Wert passt nicht.' }],
      12,
      [],
    )

    expect(unassigned).toEqual(['Dieser Wert passt nicht.'])
  })

  it('falls back to a neutral sentence when even the message is missing', () => {
    const { unassigned } = fieldErrorMessages(
      [{ field: 'somethingNew', code: 'WEIRD', message: '  ' }],
      12,
      [],
    )

    expect(unassigned).toEqual(['Die Eingabe wurde nicht angenommen.'])
  })
})

describe('passwordPolicyText', () => {
  // Both halves of the ceiling: BCrypt reads 72 bytes, so 64 umlauts are refused for a reason that
  // "höchstens 64 Zeichen" alone would not explain (ADR-0033, Entscheidung 9).
  it('names the minimum, the maximum and the byte limit', () => {
    const text = passwordPolicyText(14)

    expect(text).toContain('Mindestens 14 Zeichen')
    expect(text).toContain(`${PASSWORD_MAX_LENGTH} Zeichen`)
    expect(text).toContain(`${PASSWORD_MAX_BYTES} Byte`)
    expect(text).toContain('häufiger Passwörter')
  })
})

describe('tooManyRequestsMessage', () => {
  // The limit also counts requests of other people from the same network, so the wording must not
  // read as an accusation of "Versuchen".
  it.each([
    [30, 'in 30 Sekunden'],
    [120, 'in 2 Minuten'],
    [61, 'in 2 Minuten'],
    [null, 'später'],
    [0, 'später'],
  ])('names the wait of %s as %s', (seconds, phrase) => {
    const message = tooManyRequestsMessage(seconds)

    expect(message).toBe(
      `Es wurden zu viele Anfragen gestellt. Bitte versuchen Sie es ${phrase} erneut.`,
    )
    expect(message).not.toContain('Versuche')
  })
})
