import { delay } from 'msw'

/**
 * Fixture scenarios of the mock mode, chosen with `?mockScenario=<name>` and remembered in this
 * tab's sessionStorage so client navigation and reloads (e.g. the OAuth round trip) keep it.
 * `?mockScenario=default` returns to the regular fixtures. Tests never set it and see `default`.
 *
 * - `default`: the regular fixtures
 * - `small`: one to three entries in every listing and selection
 * - `scale`: hundreds of entries, deep folder trees and 80-120 character names
 * - `empty`: listings answer with zero entries
 * - `slow`: listings and connection tests answer after three seconds
 * - `failed`: listings fail (HTTP 403/502), connection tests report a refused sign-in
 * - `partial`: listings are incomplete, connection tests fail for some scopes
 */
export const MOCK_SCENARIOS = [
  'default',
  'small',
  'scale',
  'empty',
  'slow',
  'failed',
  'partial',
] as const

export type MockScenario = (typeof MOCK_SCENARIOS)[number]

const STORAGE_KEY = 'opaa.mockScenario'

function isScenario(value: string | null): value is MockScenario {
  return value !== null && (MOCK_SCENARIOS as readonly string[]).includes(value)
}

/** The active scenario: the query parameter wins and is remembered, then the remembered one. */
export function currentMockScenario(): MockScenario {
  if (typeof window === 'undefined') return 'default'
  try {
    const fromQuery = new URLSearchParams(window.location.search).get('mockScenario')
    if (isScenario(fromQuery)) {
      window.sessionStorage.setItem(STORAGE_KEY, fromQuery)
      return fromQuery
    }
    const stored = window.sessionStorage.getItem(STORAGE_KEY)
    return isScenario(stored) ? stored : 'default'
  } catch {
    return 'default'
  }
}

/** The caller's role on the fixture libraries, chosen with `?mockRole=<role>` next to the scenario. */
export const MOCK_ROLES = ['VIEWER', 'EDITOR', 'MANAGER', 'OWNER'] as const

export type MockRole = (typeof MOCK_ROLES)[number]

const ROLE_STORAGE_KEY = 'opaa.mockRole'

/**
 * The role override of the mock mode: `?mockRole=viewer` (any case) is remembered in this tab's
 * sessionStorage like the scenario, `?mockRole=default` drops it. Null keeps each fixture's own
 * role; tests never set it.
 */
export function currentMockRole(): MockRole | null {
  if (typeof window === 'undefined') return null
  try {
    const fromQuery = new URLSearchParams(window.location.search).get('mockRole')?.toUpperCase()
    if (fromQuery === 'DEFAULT') window.sessionStorage.removeItem(ROLE_STORAGE_KEY)
    else if (fromQuery && (MOCK_ROLES as readonly string[]).includes(fromQuery)) {
      window.sessionStorage.setItem(ROLE_STORAGE_KEY, fromQuery)
    }
    const stored = window.sessionStorage.getItem(ROLE_STORAGE_KEY)
    return stored && (MOCK_ROLES as readonly string[]).includes(stored)
      ? (stored as MockRole)
      : null
  } catch {
    return null
  }
}

/** Latency of listings and connection tests in the `slow` scenario. */
export const SLOW_SCENARIO_DELAY_MS = 3000

const SLOW_DELAY_STORAGE_KEY = 'opaa.mockSlowMs'

/**
 * The latency of the `slow` scenario: a positive whole number of milliseconds in the tab's
 * sessionStorage under `opaa.mockSlowMs` replaces the default, so a screenshot run can hold the
 * loading state open while it measures every scheme and width.
 */
export function slowScenarioDelayMs(): number {
  try {
    const stored = Number(window.sessionStorage.getItem(SLOW_DELAY_STORAGE_KEY))
    return Number.isInteger(stored) && stored > 0 ? stored : SLOW_SCENARIO_DELAY_MS
  } catch {
    return SLOW_SCENARIO_DELAY_MS
  }
}

/** Waits like a slow source in the `slow` scenario, otherwise not at all. */
export async function scenarioLatency(): Promise<void> {
  if (currentMockScenario() === 'slow') await delay(slowScenarioDelayMs())
}

/** A 35-character German compound, the longest single word the fixtures carry. */
export const LONG_COMPOUND = 'Grundstücksverkehrsgenehmigungsakte'

/** Suffixes that stretch a name to 80-120 characters, with umlauts and the long compound. */
const LONG_SUFFIXES = [
  'Fachbereich Soziales, Jugend und Familie – Abteilung Eingliederungshilfe und Teilhabe (Archiv 2009–2019)',
  `${LONG_COMPOUND}n und Flurbereinigungsverfahren der Gemarkungen Nord, Süd und Österfeld`,
  'Gemeinsame Ablage der Ämter für Straßenbau, Grünflächen und Gewässerunterhaltung – Übergabeordner',
]

/**
 * A deterministic display name for the `i`-th (1-based) generated entry: every third one is long
 * (80-120 characters), the others are short and numbered so lists sort and filter predictably.
 */
export function scenarioName(base: string, i: number, long = true): string {
  const number = String(i).padStart(3, '0')
  if (long && i % 3 === 0) {
    const suffix = LONG_SUFFIXES[(i / 3) % LONG_SUFFIXES.length]
    const name = `${base} ${number} – ${suffix}`
    // cut at a word, never inside one: „…Nord, Süd und“ rather than „…Süd und Öst“
    return name.length <= 120
      ? name
      : name
          .slice(0, name.lastIndexOf(' ', 120))
          .replace(/(,|–| und| der)$/, '')
          .trimEnd()
  }
  if (long && i === 7) return `${base} ${number} ${LONG_COMPOUND}`
  return `${base} ${number}`
}

/** `count` entries built by `make(i)`, `i` running from 1. */
export function generate<T>(count: number, make: (i: number) => T): T[] {
  return Array.from({ length: count }, (_, index) => make(index + 1))
}

/** The backend's error envelope with a German message, for a failing listing or test. */
export function scenarioErrorBody(status: number, message: string) {
  return { error: message, status, timestamp: new Date().toISOString() }
}
