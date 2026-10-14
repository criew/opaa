import { describe, expect, it } from 'vitest'
import type { MailSettingsResponse } from '@/shared/api/types'
import { mailStatusOf } from './mailStatus'

function settings(patch: Partial<MailSettingsResponse> = {}): MailSettingsResponse {
  return {
    enabled: true,
    host: 'smtp.intern.example',
    port: 587,
    username: null,
    password: null,
    passwordSet: false,
    encryption: 'STARTTLS',
    fromAddress: 'opaa@intern.example',
    fromName: null,
    lastSuccessAt: null,
    lastFailureAt: null,
    lastFailureReason: null,
    updatedAt: '2026-09-10T07:55:00Z',
    publicBaseUrlConfigured: true,
    ...patch,
  }
}

/**
 * The five readings of the status tile. „Nicht konfiguriert" and „Versand ausgeschaltet"
 * are separate: a fully stored but merely switched-off access would otherwise send someone
 * looking for settings that have long been there.
 */
describe('mailStatusOf', () => {
  it('nennt einen fehlenden Server „Nicht konfiguriert"', () => {
    expect(mailStatusOf(settings({ host: null })).kind).toBe('UNCONFIGURED')
    // even when the switch is off - the missing server is the stronger statement
    expect(mailStatusOf(settings({ host: null, enabled: false })).kind).toBe('UNCONFIGURED')
  })

  it('unterscheidet den abgeschalteten vom nicht eingerichteten Zugang', () => {
    const status = mailStatusOf(settings({ enabled: false }))
    expect(status.kind).toBe('DISABLED')
    expect(status.headline).toBe('Versand ausgeschaltet')
    expect(status.detail).toBe('Die Verbindung ist hinterlegt; es wird nichts versendet.')
  })

  it('nennt einen eingerichteten Zugang ohne jeden Versuch „Test ausstehend"', () => {
    expect(mailStatusOf(settings()).kind).toBe('UNTESTED')
  })

  it('nennt den Fehler, solange er jünger ist als der letzte Erfolg', () => {
    const status = mailStatusOf(
      settings({
        lastSuccessAt: '2026-09-10T08:00:00Z',
        lastFailureAt: '2026-09-11T08:00:00Z',
        lastFailureReason: 'Connection refused',
      }),
    )
    expect(status.kind).toBe('FAILURE')
    expect(status.detail).toBe('Connection refused')
  })

  it('nennt den Erfolg, sobald er jünger ist als der letzte Fehler', () => {
    const status = mailStatusOf(
      settings({
        lastSuccessAt: '2026-09-12T08:00:00Z',
        lastFailureAt: '2026-09-11T08:00:00Z',
        lastFailureReason: 'Connection refused',
      }),
    )
    expect(status.kind).toBe('SUCCESS')
  })
})
