import { describe, expect, it } from 'vitest'
import {
  fieldErrorMessages,
  passwordPolicyText,
  sessionEndMessage,
  tooManyRequestsMessage,
} from './authMessages'
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

describe('sessionEndMessage', () => {
  it('names a reset password only for the cause that is one', () => {
    expect(sessionEndMessage('session_revoked:admin_reset')).toContain('Ihr Passwort zurückgesetzt')
  })

  // regression guard for #1595: the general administrative act is not a password reset, and a
  // sentence that claims one sends the person after a password nobody issued.
  it('says nothing about a password for the general administrative act', () => {
    const message = sessionEndMessage('session_revoked:admin_action')

    expect(message).toBe(
      'Ihre Sitzung wurde von der Systemverwaltung beendet. Bitte melden Sie sich erneut an.',
    )
    expect(message).not.toContain('Passwort')
  })

  // The switch-off is what a regular local account reads once the management is closed; it names
  // no way back that does not exist.
  it('sends the person to the administration when the local management is off', () => {
    expect(sessionEndMessage('local_accounts_disabled')).toBe(
      'Die Anmeldung mit Konten dieser Installation wurde abgeschaltet. Bitte wenden Sie sich an die Systemverwaltung.',
    )
  })

  // An unknown cause falls back to its marker's sentence rather than to the bare "expired".
  it('falls back to the marker sentence for a cause it does not know', () => {
    expect(sessionEndMessage('session_revoked:etwas_neues' as never)).toBe(
      'Ihre Sitzung wurde beendet. Bitte melden Sie sich erneut an.',
    )
  })
})
