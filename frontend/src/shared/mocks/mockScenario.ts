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

/** Latency of listings and connection tests in the `slow` scenario. */
export const SLOW_SCENARIO_DELAY_MS = 3000

/** Waits like a slow source in the `slow` scenario, otherwise not at all. */
export async function scenarioLatency(): Promise<void> {
  if (currentMockScenario() === 'slow') await delay(SLOW_SCENARIO_DELAY_MS)
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
    return `${base} ${number} – ${suffix}`.slice(0, 120)
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
