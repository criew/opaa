import { describe, expect, it } from 'vitest'
import {
  clientSetupSnippets,
  earliestExpiryDate,
  expiryInstantOf,
  expiryWarning,
  maxExpiryDate,
  toDateInputValue,
} from './tokenLabels'

/** The derivations around the expiry - the part where an error silently leads to a 400. */
describe('tokenLabels', () => {
  it('rechnet die Höchstlaufzeit in Stunden, nicht in Kalendertagen', () => {
    // Regression guard: across a daylight saving change a calendar day is not 24 hours long.
    // The interface calculates in hours and rejected an upper bound calculated that way.
    const now = new Date('2026-09-18T10:00:00Z')

    expect(maxExpiryDate(90, now).getTime() - now.getTime()).toBe(90 * 86_400_000)
  })

  it('kürzt den letzten wählbaren Tag mit Abstand unter die Höchstlaufzeit', () => {
    const latest = new Date('2026-12-17T10:00:00Z')

    // The margin absorbs a device clock running ahead: the interface checks against its own,
    // and hit exactly, the upper bound would already be exceeded there.
    expect(expiryInstantOf(toDateInputValue(latest), latest)).toBe(
      new Date('2026-12-17T09:00:00Z').toISOString(),
    )
  })

  it('setzt den frühesten Ablauftag auf morgen', () => {
    const now = new Date('2026-09-18T10:00:00Z')

    expect(earliestExpiryDate(now).getTime() - now.getTime()).toBe(86_400_000)
  })

  it('nimmt für jeden früheren Tag dessen Ende', () => {
    const latest = new Date('2026-12-17T10:00:00Z')
    const earlier = new Date(2026, 10, 2, 8, 0, 0)

    expect(expiryInstantOf(toDateInputValue(earlier), latest)).toBe(
      new Date(2026, 10, 2, 23, 59, 59).toISOString(),
    )
  })

  it('warnt erst innerhalb der Frist und nie an einem toten Token', () => {
    const now = new Date('2026-09-18T10:00:00Z')

    expect(expiryWarning('ACTIVE', '2026-12-01T10:00:00Z', now)).toBeNull()
    expect(expiryWarning('ACTIVE', '2026-09-25T10:00:00Z', now)).toBe('Läuft in 7 Tagen ab')
    expect(expiryWarning('ACTIVE', '2026-09-19T10:00:00Z', now)).toBe('Läuft morgen ab')
    expect(expiryWarning('REVOKED', '2026-09-19T10:00:00Z', now)).toBeNull()
  })

  it('setzt den Wert in die Einrichtungsschnipsel und hängt /mcp an die Adresse', () => {
    const snippets = clientSetupSnippets('https://opaa.example/', 'opaa_pat_wert')

    expect(snippets.url).toBe('https://opaa.example/mcp')
    // --scope user: the entry applies across projects instead of only in the current project, and
    // the shareable storage (--scope project, .mcp.json in the project) is thus never considered.
    expect(snippets.claudeCode).toContain('claude mcp add --scope user --transport http opaa')
    expect(snippets.claudeCode).toContain('Authorization: Bearer opaa_pat_wert')
  })

  it('hält den Wert aus den beiden JSON-Schnipseln heraus', () => {
    const snippets = clientSetupSnippets('https://opaa.example/', 'opaa_pat_wert')

    const vsCode = JSON.parse(snippets.vsCode)
    expect(vsCode.servers.opaa.type).toBe('http')
    expect(vsCode.servers.opaa.url).toBe('https://opaa.example/mcp')
    expect(vsCode.servers.opaa.headers.Authorization).toBe('Bearer ${input:opaa-token}')
    expect(vsCode.inputs[0]).toMatchObject({ id: 'opaa-token', password: true })

    const cursor = JSON.parse(snippets.cursor)
    expect(cursor.mcpServers.opaa.url).toBe('https://opaa.example/mcp')
    expect(cursor.mcpServers.opaa.headers.Authorization).toBe('Bearer ${env:OPAA_TOKEN}')

    // The plain text is only in the Claude Code command: experience shows both files end up in
    // a repository or a synchronized profile.
    expect(snippets.vsCode).not.toContain('opaa_pat_wert')
    expect(snippets.cursor).not.toContain('opaa_pat_wert')
  })
})
