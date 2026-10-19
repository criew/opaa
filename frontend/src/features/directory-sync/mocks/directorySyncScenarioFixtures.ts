import type {
  DirectorySyncOutcome,
  DirectorySyncPendingPlanResponse,
  DirectorySyncStatusResponse,
  OidcProviderResponse,
} from '@/shared/api/types'
import { mockPendingPlan } from '../../group/mocks/groupAdminFixtures'

/**
 * The directory sync with many providers in every state the overview distinguishes - pending
 * plan, failed run, missing access, paused, groups from the token, never run. The browser worker
 * serves it instead of the regular fixtures when the page is opened with
 * `?mockScenario=many-directories`; tests install it with `server.use`.
 */
export const MANY_DIRECTORIES_SCENARIO = 'many-directories'

interface ScenarioRow {
  name: string
  enabled?: boolean
  sync?: boolean
  interval?: number
  groupsClaim?: string
  connector?: boolean
  /** Hours before now. */
  lastRunHoursAgo?: number
  outcome?: DirectorySyncOutcome
  message?: string
  plan?: { daysAgo: number; removed: number; locked: number }
}

const ROWS: ScenarioRow[] = [
  {
    name: 'Verzeichnisdienst',
    lastRunHoursAgo: 2,
    outcome: 'PENDING_CONFIRMATION',
    message: 'Über der Schwelle: Der Plan wartet auf eine Entscheidung.',
    plan: { daysAgo: 3, removed: 23, locked: 1 },
  },
  { name: 'Personalverwaltung', lastRunHoursAgo: 1, outcome: 'APPLIED' },
  {
    name: 'Hochschule Nord',
    interval: 60,
    lastRunHoursAgo: 0.5,
    outcome: 'UNREACHABLE',
    message: 'Verzeichnis nicht erreichbar: Zeitüberschreitung nach 30 Sekunden.',
  },
  { name: 'Landesportal', sync: false },
  { name: 'Partnerportal', sync: false, groupsClaim: 'groups' },
  { name: 'Bauamt', interval: 1440, lastRunHoursAgo: 5, outcome: 'APPLIED' },
  { name: 'Gesundheitsamt', enabled: false, lastRunHoursAgo: 240, outcome: 'APPLIED' },
  { name: 'Schulverwaltung', connector: false },
  {
    name: 'Finanzamt Süd',
    interval: 720,
    lastRunHoursAgo: 4,
    outcome: 'PENDING_CONFIRMATION',
    message: 'Über der Schwelle: Der Plan wartet auf eine Entscheidung.',
    plan: { daysAgo: 0.2, removed: 4, locked: 0 },
  },
  { name: 'Stadtwerke', interval: 30, lastRunHoursAgo: 0.2, outcome: 'APPLIED' },
  { name: 'Kreisarchiv' },
  {
    name: 'Polizeipräsidium',
    lastRunHoursAgo: 3,
    outcome: 'ABORTED_EMPTY_RESULT',
    message: 'Abgebrochen: Das Verzeichnis lieferte keine einzige Gruppe.',
  },
  { name: 'Jugendamt', sync: false },
  {
    name: 'Landratsamt',
    lastRunHoursAgo: 1.5,
    outcome: 'FAILED',
    message:
      'Der Abgleich ist mit einem Fehler abgebrochen und hat nichts geändert. Der letzte bekannte Stand bleibt in Kraft; die Ursache steht im Anwendungsprotokoll.',
  },
]

/** Switches made in the browser while the scenario is open; a reload starts over. */
const switched = new Map<string, { sync: boolean; interval: number | null }>()

export function switchScenarioSync(providerId: string, sync: boolean, interval: number | null) {
  switched.set(providerId, { sync, interval })
}

function withSwitches(row: ScenarioRow): ScenarioRow {
  const change = switched.get(idOf(row.name))
  if (!change) return row
  return { ...row, sync: change.sync, interval: change.interval ?? row.interval }
}

function idOf(name: string): string {
  return `scenario-${name
    .toLowerCase()
    .replace(/ä/g, 'ae')
    .replace(/ö/g, 'oe')
    .replace(/ü/g, 'ue')
    .replace(/[^a-z0-9]+/g, '-')}`
}

function hoursAgo(hours: number, now: number): string {
  return new Date(now - hours * 3_600_000).toISOString()
}

function providerOf(row: ScenarioRow, index: number): OidcProviderResponse {
  const sync = row.sync ?? true
  return {
    id: idOf(row.name),
    displayName: row.name,
    enabled: row.enabled ?? true,
    isDefault: index === 0,
    isExternal: index > 0,
    providerType: 'OIDC',
    directorySyncEnabled: sync,
    directorySyncIntervalMinutes: sync ? (row.interval ?? 360) : null,
    directoryConnector:
      row.connector === false || !sync
        ? null
        : {
            type: 'KEYCLOAK',
            baseUrl: 'http://keycloak:8180',
            realm: idOf(row.name).replace('scenario-', ''),
            clientId: 'opaa-directory',
            updatedAt: '2026-09-01T08:00:00Z',
          },
    sortOrder: index + 1,
    issuerUri: `https://login.example/realms/${idOf(row.name)}`,
    clientId: 'opaa-frontend',
    jwkSetUri: null,
    claimMapping: {
      emailClaim: 'email',
      displayNameClaim: 'name',
      rolesClaim: null,
      systemAdminRole: null,
      auditorRole: null,
      groupsClaim: row.groupsClaim ?? null,
    },
    registryState: row.enabled === false ? 'DISABLED' : 'READY',
    registryMessage: null,
    createdAt: '2026-09-01T08:00:00Z',
    updatedAt: '2026-09-01T08:00:00Z',
  }
}

/** As the backend writes it: an unreachable or failed run measured nothing and records 0. */
function changedFractionOf(row: ScenarioRow): number | null {
  if (!row.outcome) return null
  if (row.outcome === 'UNREACHABLE' || row.outcome === 'FAILED') return 0
  return row.plan ? 0.42 : 0.03
}

function statusOf(row: ScenarioRow, now: number): DirectorySyncStatusResponse {
  const sync = row.sync ?? true
  const lastRunAt = row.lastRunHoursAgo === undefined ? null : hoursAgo(row.lastRunHoursAgo, now)
  return {
    providerId: idOf(row.name),
    providerDisplayName: row.name,
    providerEnabled: row.enabled ?? true,
    enabled: sync,
    intervalMinutes: sync ? (row.interval ?? 360) : null,
    lastRunAt,
    lastOutcome: row.outcome ?? null,
    lastMessage: row.outcome ? (row.message ?? 'Synchronisation angewendet.') : null,
    lastAppliedAt: row.outcome === 'APPLIED' ? lastRunAt : null,
    lastChangedFraction: changedFractionOf(row),
    pendingPlan: row.plan
      ? {
          id: `${idOf(row.name)}-plan`,
          createdAt: hoursAgo(row.plan.daysAgo * 24, now),
          changedFraction: 0.42,
          membershipsRemoved: row.plan.removed,
          accountsLocked: row.plan.locked,
        }
      : null,
  }
}

export function manyDirectoryProviders(): OidcProviderResponse[] {
  return ROWS.map(withSwitches).map(providerOf)
}

/** Only providers that have the sync on or ever ran have a status line, as in the backend. */
export function manyDirectoryStatuses(now: number = Date.now()): DirectorySyncStatusResponse[] {
  return ROWS.map(withSwitches)
    .filter((row) => (row.sync ?? true) || row.lastRunHoursAgo !== undefined)
    .map((row) => statusOf(row, now))
}

export function manyDirectoryPendingPlan(
  providerId: string,
): DirectorySyncPendingPlanResponse | null {
  const status = manyDirectoryStatuses().find((entry) => entry.providerId === providerId)
  if (!status?.pendingPlan) return null
  return {
    ...mockPendingPlan,
    id: status.pendingPlan.id,
    providerId,
    createdAt: status.pendingPlan.createdAt,
  }
}

/** Whether the page in the browser asked for this scenario; never in tests (no such URL). */
export function isManyDirectoriesScenario(): boolean {
  if (typeof window === 'undefined') return false
  return (
    new URLSearchParams(window.location.search).get('mockScenario') === MANY_DIRECTORIES_SCENARIO
  )
}
