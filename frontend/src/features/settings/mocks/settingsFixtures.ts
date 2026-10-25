import type { SettingsDocument } from '@/shared/api/types'

/** A small export in the shape of the backend's, two sections only. */
export function settingsDocumentFixture(): SettingsDocument {
  return {
    format: 'opaa-settings',
    formatVersion: 1,
    exportedAt: '2026-10-25T09:00:00Z',
    sections: {
      auditRetention: { retentionMonths: 36 },
      linkTargets: { allowedOrigins: [] },
    },
  }
}
