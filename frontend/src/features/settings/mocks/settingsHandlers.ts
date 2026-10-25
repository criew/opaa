import { http, HttpResponse } from 'msw'
import type { SettingsChange, SettingsDocument, SettingsImportResult } from '@/shared/api/types'
import { settingsDocumentFixture } from './settingsFixtures'

/** The mock installation's settings; an import replaces the values the document carries. */
export let mockSettings: SettingsDocument = settingsDocumentFixture()

export function resetMockSettings(): void {
  mockSettings = settingsDocumentFixture()
}

const LABELS: Record<string, string> = {
  auditRetention: 'Aufbewahrungsfrist des Protokolls',
  linkTargets: 'Erlaubte Linkziele',
}

const KEY_LABELS: Record<string, string> = {
  retentionMonths: 'Aufbewahrungsfrist in Monaten',
  allowedOrigins: 'Erlaubte Linkziele',
}

const SECURITY_RELEVANT = new Set(['allowedOrigins'])

function changesOf(document: SettingsDocument): SettingsChange[] | string {
  const changes: SettingsChange[] = []
  for (const [section, values] of Object.entries(document.sections)) {
    const current = mockSettings.sections[section]
    if (!current) return `Diese Installation kennt diese Abschnitte nicht: ${section}.`
    for (const [key, after] of Object.entries(values)) {
      if (!(key in current)) return `Der Abschnitt „${LABELS[section]}“ kennt ${key} nicht.`
      if (JSON.stringify(current[key]) !== JSON.stringify(after)) {
        changes.push({
          section,
          sectionLabel: LABELS[section],
          key,
          keyLabel: KEY_LABELS[key] ?? key,
          securityRelevant: SECURITY_RELEVANT.has(key),
          before: current[key],
          after,
        })
      }
    }
  }
  return changes
}

function refused(message: string) {
  return HttpResponse.json(
    { error: message, status: 400, timestamp: new Date().toISOString() },
    { status: 400 },
  )
}

async function check(request: Request, apply: boolean) {
  const document = (await request.json()) as SettingsDocument
  const changes = changesOf(document)
  if (typeof changes === 'string') return refused(changes)
  if (apply) {
    for (const change of changes) {
      mockSettings.sections[change.section][change.key] = change.after
    }
  }
  const result: SettingsImportResult = { applied: apply, changes }
  return HttpResponse.json(result)
}

export const settingsHandlers = [
  http.get('/api/v1/system/settings/export', () =>
    HttpResponse.json({ ...mockSettings, exportedAt: new Date().toISOString() }),
  ),
  http.post('/api/v1/system/settings/import/preview', ({ request }) => check(request, false)),
  http.post('/api/v1/system/settings/import', ({ request }) => check(request, true)),
]
