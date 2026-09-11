import { describe, expect, it } from 'vitest'
import type { MailSettingsResponse } from '../../../types/api'
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
 * Die fünf Lesarten der Statuskachel (#1542). „Nicht konfiguriert" und „Versand ausgeschaltet"
 * sind getrennt: Ein vollständig hinterlegter, bloß abgeschalteter Zugang schickt sonst jemanden
 * auf die Suche nach Einstellungen, die längst da sind.
 */
describe('mailStatusOf', () => {
  it('nennt einen fehlenden Server „Nicht konfiguriert"', () => {
    expect(mailStatusOf(settings({ host: null })).kind).toBe('UNCONFIGURED')
    // auch dann, wenn der Schalter aus ist - der fehlende Server ist die stärkere Aussage
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
