import type { AssetRole, Capability, SpaceRole } from '../api/types'

const spaceRoleLabels: Record<SpaceRole, string> = {
  MEMBER: 'Mitglied',
  CURATOR: 'Kurator',
  ADMIN: 'Administrator',
}

export function spaceRoleLabel(role: SpaceRole | string | undefined): string {
  if (!role) return ''
  return spaceRoleLabels[role as SpaceRole] ?? role
}

/**
 * The document figure of an overview: the plain number, shortened from five digits on - "9.999",
 * "120 K", "1,2 Mio." - so the column stays readable at a glance. Rounding never carries a value
 * across the next order of magnitude: 999.999 reads "1 Mio.", never "1.000 K".
 */
export function documentCountLabel(count: number): string {
  if (count < 10_000) return count.toLocaleString('de-DE')
  if (Math.round(count / 1_000) < 1_000) return `${Math.round(count / 1_000)} K`
  const millions = (count / 1_000_000).toFixed(1)
  return `${(millions.endsWith('.0') ? millions.slice(0, -2) : millions).replace('.', ',')} Mio.`
}

/** What "Alle Konten" is called wherever a grant names its recipient (#1931). */
export const allAccountsLabel = 'Alle Konten'

const assetRoleLabels: Record<AssetRole, string> = {
  VIEWER: 'Leser',
  EDITOR: 'Bearbeiter',
  MANAGER: 'Verwalter',
  OWNER: 'Eigentümer',
}

export function assetRoleLabel(role: AssetRole | string | undefined): string {
  if (!role) return ''
  return assetRoleLabels[role as AssetRole] ?? role
}

/** Formats a byte count as a German-locale size string (e.g. "1,2 MB"), or an em dash if unknown. */
export function formatFileSize(bytes: number | null | undefined): string {
  if (bytes == null) return '—'
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB', 'TB']
  let value = bytes / 1024
  let unitIndex = 0
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024
    unitIndex += 1
  }
  return `${value.toLocaleString('de-DE', { maximumFractionDigits: 1 })} ${units[unitIndex]}`
}

/**
 * A 0..1 share as a whole German percentage - the one rendering of the Füllgrad and the
 * Pflege-Anker (#1069), so the same figure never appears as "64 %" in one place and "63,6 %" in
 * another.
 */
export function formatShare(share: number): string {
  return `${Math.round(share * 100)} %`
}

const capabilityLabels: Record<Capability, string> = {
  CREATE_SPACE: 'Spaces anlegen',
  CREATE_LIBRARY: 'Bibliotheken für Uploads anlegen',
  CREATE_CONNECTOR_LIBRARY: 'Konnektorbibliotheken anlegen',
  CREATE_INTERNAL_GROUP: 'Interne Gruppen anlegen',
  CREATE_PROMPT_LIBRARY: 'Prompt-Bibliotheken anlegen',
}

/**
 * The sentence a creation dialog shows for a missing Anlegerecht - word for word the one the
 * backend answers with, so the explanation before the attempt and the refusal after it do not
 * differ. `CapabilityService#requireCapability` holds the other copy; its wording is asserted in
 * `CapabilityEnforcementIntegrationTest`.
 */
export function capabilityMissingMessage(capability: Capability): string {
  return (
    `Ihnen fehlt das Anlegerecht „${capabilityLabels[capability]}“. ` +
    'Wenden Sie sich an die Systemverwaltung, wenn Sie es benötigen.'
  )
}

/**
 * A calendar day the API sends as `YYYY-MM-DD` in German notation (`03.11.2026`), without a
 * detour through a time zone that could shift it by a day.
 */
export function formatCalendarDay(isoDay: string): string {
  const [year, month, day] = isoDay.split('-')
  return `${day}.${month}.${year}`
}
