import type { DirectorySyncStatusResponse, OidcProviderResponse } from '@/shared/api/types'
import type { StatusTone } from '@/shared/ui/StatusLine'
import { ageSinceLabel, countLabel } from '../../groups/groupOriginLabels'

/** How the sync of one provider stands, read from the provider row and its status line. */
export type SyncStateKind = 'ON' | 'PAUSED' | 'TOKEN' | 'OFF'

export interface SyncState {
  kind: SyncStateKind
  label: string
  tone: StatusTone
}

export interface Attention {
  tone: 'warning' | 'error'
  /** Two or three words for the overview column. */
  label: string
  /** The sentence behind it. */
  detail: string
}

export type NextRun = { kind: 'NONE' } | { kind: 'DUE' } | { kind: 'AT'; at: Date }

export type OverviewFilter = 'ALL' | 'ATTENTION' | 'ON'

export interface OverviewEntry {
  provider: OidcProviderResponse
  status: DirectorySyncStatusResponse | undefined
  attention: Attention | null
}

const MINUTES_PER_HOUR = 60
const MINUTES_PER_DAY = 1440
const MINUTES_PER_WEEK = 10080

function hasGroupsClaim(provider: OidcProviderResponse): boolean {
  return Boolean(provider.claimMapping.groupsClaim)
}

/** The provider's one group mechanism (ADR-0036, Entscheidung 2) and the state of the sync. */
export function syncStateOf(provider: OidcProviderResponse): SyncState {
  if (provider.directorySyncEnabled) {
    return provider.enabled
      ? { kind: 'ON', label: 'Eingeschaltet', tone: 'success' }
      : { kind: 'PAUSED', label: 'Pausiert', tone: 'neutral' }
  }
  if (hasGroupsClaim(provider)) {
    return { kind: 'TOKEN', label: 'Gruppen aus dem Token', tone: 'neutral' }
  }
  return { kind: 'OFF', label: 'Aus', tone: 'neutral' }
}

/**
 * What an administrator has to do for this provider, the loudest first: a pending plan waits for
 * a decision (ADR-0036, Entscheidung 3), a failed run keeps the last known state in force, and a
 * running sync without directory access cannot read anything.
 */
export function attentionOf(
  provider: OidcProviderResponse,
  status: DirectorySyncStatusResponse | undefined,
  now: Date = new Date(),
): Attention | null {
  const plan = status?.pendingPlan
  if (plan) {
    const effects = [
      plan.membershipsRemoved > 0 &&
        `${countLabel(plan.membershipsRemoved, 'Mitgliedschaft', 'Mitgliedschaften')} entziehen`,
      (plan.accountsLocked ?? 0) > 0 &&
        `${countLabel(plan.accountsLocked ?? 0, 'Konto', 'Konten')} sperren`,
    ].filter((effect): effect is string => Boolean(effect))
    const waiting = `Plan wartet seit ${ageSinceLabel(plan.createdAt, now)}`
    return {
      tone: 'warning',
      label: 'Entscheidung nötig',
      detail: effects.length > 0 ? `${waiting} · würde ${effects.join(' und ')}` : waiting,
    }
  }
  if (!provider.directorySyncEnabled || !provider.enabled) return null
  if (status?.lastOutcome === 'UNREACHABLE' || status?.lastOutcome === 'ABORTED_EMPTY_RESULT') {
    return {
      tone: 'error',
      label: 'Letzter Lauf gescheitert',
      detail: status.lastMessage ?? 'Der letzte Lauf hat nichts angewendet.',
    }
  }
  if (!provider.directoryConnector) {
    return {
      tone: 'warning',
      label: 'Verzeichniszugang fehlt',
      detail: 'Ohne Zugang meldet jeder Lauf das Verzeichnis als nicht erreichbar.',
    }
  }
  return null
}

/** When the schedule runs next: one interval after the last run, at once if it never ran. */
export function nextRunOf(
  provider: OidcProviderResponse,
  status: DirectorySyncStatusResponse | undefined,
  now: Date = new Date(),
): NextRun {
  if (!provider.directorySyncEnabled || !provider.enabled) return { kind: 'NONE' }
  const interval = provider.directorySyncIntervalMinutes ?? status?.intervalMinutes
  if (!status?.lastRunAt || !interval) return { kind: 'DUE' }
  const at = new Date(new Date(status.lastRunAt).getTime() + interval * 60_000)
  return at.getTime() <= now.getTime() ? { kind: 'DUE' } : { kind: 'AT', at }
}

/** The interval in the largest whole unit: „alle 6 Stunden", „täglich". */
export function intervalLabel(minutes: number): string {
  if (minutes === MINUTES_PER_WEEK) return 'wöchentlich'
  if (minutes % MINUTES_PER_DAY === 0) {
    const days = minutes / MINUTES_PER_DAY
    return days === 1 ? 'täglich' : `alle ${days} Tage`
  }
  if (minutes % MINUTES_PER_HOUR === 0) {
    const hours = minutes / MINUTES_PER_HOUR
    return hours === 1 ? 'stündlich' : `alle ${hours} Stunden`
  }
  return `alle ${minutes} Minuten`
}

/** A coming time on the clock: „heute, 15:00" or „21.09., 06:05". */
export function clockLabel(at: Date, now: Date = new Date()): string {
  const time = at.toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' })
  if (at.toDateString() === now.toDateString()) return `heute, ${time}`
  const day = at.toLocaleDateString('de-DE', { day: '2-digit', month: '2-digit' })
  return `${day}, ${time}`
}

/** A past time as its age: „vor 3 Stunden". */
export function agoLabel(iso: string, now: Date = new Date()): string {
  return `vor ${ageSinceLabel(iso, now)}`
}

/** Why the sync cannot be switched on - the backend answers 409 in exactly this case. */
export function switchOnBlocker(provider: OidcProviderResponse): string | null {
  if (provider.directorySyncEnabled || !hasGroupsClaim(provider)) return null
  return `Dieser Anbieter liefert seine Gruppen über den Gruppen-Claim „${provider.claimMapping.groupsClaim}“. Je Anbieter gibt es genau einen Gruppenmechanismus: Leeren Sie zuerst den Gruppen-Claim in der Anbieterverwaltung.`
}

/** Why no run can be started by hand - the backend refuses both cases with 409. */
export function runBlocker(provider: OidcProviderResponse): string | null {
  if (!provider.directorySyncEnabled) {
    return 'Trockenlauf und Abgleich gehen erst, wenn der Abgleich eingeschaltet ist.'
  }
  if (!provider.enabled) {
    return 'Der Anbieter ist deaktiviert; sein Abgleich pausiert, solange das so bleibt.'
  }
  return null
}

/** Why a pending plan cannot be confirmed - a confirmation is a run and refused like one. */
export function confirmBlocker(provider: OidcProviderResponse): string | null {
  if (!provider.directorySyncEnabled) {
    return 'Bestätigen geht erst, wenn der Abgleich eingeschaltet ist. Verwerfen können Sie den Plan jederzeit.'
  }
  if (!provider.enabled) {
    return 'Bestätigen geht erst, wenn der Anbieter wieder aktiviert ist. Verwerfen können Sie den Plan jederzeit.'
  }
  return null
}

export function matchesFilter(
  entry: OverviewEntry,
  filter: OverviewFilter,
  query: string,
): boolean {
  if (filter === 'ATTENTION' && !entry.attention) return false
  if (filter === 'ON' && !entry.provider.directorySyncEnabled) return false
  const needle = query.trim().toLocaleLowerCase('de-DE')
  return needle === '' || entry.provider.displayName.toLocaleLowerCase('de-DE').includes(needle)
}
