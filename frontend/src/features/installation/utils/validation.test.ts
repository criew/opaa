import { describe, expect, it } from 'vitest'
import { byField, compact, emailError, hexColorError, urlError } from './validation'

describe('installation validation', () => {
  it('accepts a deliverable-looking address and refuses the reserved one', () => {
    expect(emailError('it-betrieb@stadt.example')).toBeUndefined()
    expect(emailError('')).toBe('Bitte eine E-Mail-Adresse angeben.')
    expect(emailError('it-betrieb')).toMatch(/nicht vollständig/)
    expect(emailError(' Admin@OPAA.local ')).toMatch(/nicht zulässig/)
  })

  it('accepts only absolute http(s) addresses', () => {
    expect(urlError('http://ollama:11434/v1', 'die Basis-Adresse')).toBeUndefined()
    expect(urlError('', 'die Basis-Adresse')).toBe('Bitte die Basis-Adresse angeben.')
    expect(urlError('', 'die JWK-Set-Adresse', true)).toBeUndefined()
    expect(urlError('ollama:11434', 'die Basis-Adresse')).toBe(
      'Die Basis-Adresse beginnt mit http:// oder https://.',
    )
    expect(urlError('ftp://host', 'die Basis-Adresse')).toMatch(/beginnt mit http/)
  })

  it('accepts a six-digit hex colour', () => {
    expect(hexColorError('#1292EE')).toBeUndefined()
    expect(hexColorError('blau')).toMatch(/Hex-Wert/)
  })

  it('keeps only fields with a message, and the first backend message per field', () => {
    expect(compact({ a: 'x', b: undefined })).toEqual({ a: 'x' })
    expect(
      byField([
        { field: 'password', code: 'TOO_SHORT', message: 'zu kurz' },
        { field: 'password', code: 'TOO_COMMON', message: 'zu häufig' },
      ]),
    ).toEqual({ password: 'zu kurz' })
  })
})
