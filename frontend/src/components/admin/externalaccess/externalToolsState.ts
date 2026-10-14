import type { ExternalAccessSettingsResponse } from '@/shared/api/types'
import type { StatusTone } from '@/shared/ui/StatusLine'
import { formatDate } from '../../externalaccess/tokenLabels'

/** From how many days before its end a release is marked as running out. */
export const RELEASE_ENDING_DAYS = 30

export interface ChannelState {
  tone: StatusTone
  headline: string
  detail: string
}

function changedLine(settings: ExternalAccessSettingsResponse): string {
  return `Zuletzt geändert am ${formatDate(settings.updatedAt)} durch ${settings.updatedBy ?? 'die Installation selbst'}.`
}

function count(value: number, one: string, many: string): string {
  return value === 1 ? `1 ${one}` : `${value.toLocaleString('de-DE')} ${many}`
}

/**
 * The stored state of the channel as one line: off, on, or on without any network range - the
 * last one admits nobody and is the only state that asks for action.
 */
export function channelStateOf(settings: ExternalAccessSettingsResponse): ChannelState {
  if (!settings.enabled) {
    return {
      tone: 'neutral',
      headline: 'Ausgeschaltet',
      detail: `Externe KI-Werkzeuge erreichen OPAA nicht. ${changedLine(settings)}`,
    }
  }
  if (settings.allowedCidrs.length === 0) {
    return {
      tone: 'warning',
      headline: 'Eingeschaltet, aber für jede Adresse geschlossen',
      detail: `Es ist kein Netzbereich eingetragen; der Weg antwortet deshalb keinem Aufrufer. ${changedLine(settings)}`,
    }
  }
  return {
    tone: 'success',
    headline: 'Eingeschaltet',
    detail: `${count(settings.allowedCidrs.length, 'Netzbereich', 'Netzbereiche')} · ${count(settings.tokenRateLimitPerHour, 'Anfrage', 'Anfragen')} je Token und Stunde · Tokens höchstens ${count(settings.tokenMaxLifetimeDays, 'Tag', 'Tage')} gültig. ${changedLine(settings)}`,
  }
}

/** „läuft in 12 Tagen ab" for a release ending within {@link RELEASE_ENDING_DAYS}, else null. */
export function releaseEndingLabel(
  expiresAt: string | null | undefined,
  now: Date = new Date(),
): string | null {
  if (!expiresAt) return null
  const expiry = new Date(expiresAt)
  if (Number.isNaN(expiry.getTime())) return null
  // Calendar days in the device's zone: an end tonight is „heute", not „in einem Tag".
  const startOf = (date: Date) =>
    new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
  const days = Math.round((startOf(expiry) - startOf(now)) / 86_400_000)
  if (days > RELEASE_ENDING_DAYS) return null
  if (days <= 0) return 'läuft heute ab'
  if (days === 1) return 'läuft morgen ab'
  return `läuft in ${days} Tagen ab`
}
