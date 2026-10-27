import { describe, expect, it } from 'vitest'
import {
  SMTP_TEXTS,
  isMailAddress,
  parseSmtpPort,
  smtpPortError,
  smtpTargetChanged,
} from './smtpSettings'

describe('smtpSettings', () => {
  it('reads a port between 1 and 65535 and leaves an empty field empty', () => {
    expect(parseSmtpPort(' 587 ')).toBe(587)
    expect(parseSmtpPort('')).toBeNull()
    expect(parseSmtpPort('0')).toBeNaN()
    expect(parseSmtpPort('65536')).toBeNaN()
    expect(parseSmtpPort('25a')).toBeNaN()
  })

  it('names only a port that is none', () => {
    expect(smtpPortError('')).toBeUndefined()
    expect(smtpPortError('465')).toBeUndefined()
    expect(smtpPortError('70000')).toBe(SMTP_TEXTS.portInvalid)
  })

  it('accepts what has the form of a mail address', () => {
    expect(isMailAddress(' it@stadt.example ')).toBe(true)
    expect(isMailAddress('it@stadt')).toBe(false)
  })

  it('sees another target in a changed server, port, account or encryption only', () => {
    const stored = {
      host: 'smtp.intern',
      port: 587,
      username: 'opaa',
      encryption: 'STARTTLS' as const,
    }
    expect(smtpTargetChanged(stored, { ...stored, host: ' smtp.intern ' })).toBe(false)
    expect(smtpTargetChanged(stored, { ...stored, host: 'smtp.other' })).toBe(true)
    expect(smtpTargetChanged(stored, { ...stored, port: 465 })).toBe(true)
    expect(smtpTargetChanged(stored, { ...stored, username: '' })).toBe(true)
    expect(smtpTargetChanged(stored, { ...stored, encryption: 'SSL' })).toBe(true)
  })
})
