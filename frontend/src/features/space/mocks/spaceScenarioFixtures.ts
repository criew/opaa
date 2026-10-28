import type { SpaceListResponse } from '@/shared/api/types'
import { currentMockScenario, generate, LONG_COMPOUND } from '@/shared/mocks/mockScenario'
import { defaultChatAutoCleanup, mockSpaceDetails, mockSpaces } from './spaceFixtures'

/**
 * The space list under the fixture scenarios of `@/shared/mocks/mockScenario`: `scale` adds
 * {@link SCALE_SPACE_NAMES} (some 80-120 characters long), `small` keeps the personal space and
 * Engineering, `empty` only the personal space. Generated spaces get a detail entry as well, so
 * their pages and chat lists answer like the fixtures.
 */
export const SCALE_SPACE_NAMES = [
  'Bürgerbüro',
  'Bauaufsicht und Denkmalschutz',
  'Referat 50 – Soziales',
  'Haushalt 2027',
  `${LONG_COMPOUND}n – Abstimmung mit dem Katasteramt und der Unteren Naturschutzbehörde`,
  'Personalrat',
  'IT-Koordination',
  'Ordnungsamt',
  'Vergabestelle',
  'Fachbereich Soziales, Jugend und Familie – Abteilung Eingliederungshilfe und Teilhabe',
  'Kita-Bedarfsplanung',
  'Standesamt',
  'Feuerwehr und Katastrophenschutz',
  'Ratsbüro',
  'Wirtschaftsförderung',
  'Digitalisierung Antragsverfahren (OZG)',
  'Gemeinsame Ablage der Ämter für Straßenbau, Grünflächen und Gewässerunterhaltung',
  'Schulverwaltung',
  'Kämmerei',
  'Pressestelle',
  'Umweltamt',
  'Gleichstellung',
  'Rechnungsprüfung',
  'Archiv',
]

function scaleSpaces(): SpaceListResponse[] {
  return generate(SCALE_SPACE_NAMES.length, (i) => ({
    id: `space-scale-${i}`,
    name: SCALE_SPACE_NAMES[i - 1],
    description: null,
    isDefault: false,
    archived: i === SCALE_SPACE_NAMES.length,
    memberCount: ((i * 7) % 40) + 2,
    memberships: { groupCount: i % 3, userCount: ((i * 7) % 40) + 2 },
    libraryCount: i % 5,
    chatCount: 5,
    userRole: i % 4 === 0 ? 'MEMBER' : 'ADMIN',
    createdAt: '2026-04-01T10:00:00Z',
    updatedAt: '2026-04-01T10:00:00Z',
  }))
}

/** Registers the detail entries of the scenario's generated spaces; idempotent. */
export function ensureScenarioSpaces(): void {
  if (currentMockScenario() !== 'scale') return
  for (const space of scaleSpaces()) {
    if (mockSpaceDetails[space.id]) continue
    mockSpaceDetails[space.id] = {
      id: space.id,
      name: space.name,
      description: space.description,
      isDefault: false,
      archived: space.archived,
      ownerId: 'owner-1',
      memberCount: space.memberCount,
      memberships: space.memberships,
      userRole: space.userRole,
      chatAutoCleanup: { ...defaultChatAutoCleanup },
      roleCounts: { MEMBER: space.memberCount - 1, CURATOR: 0, ADMIN: 1 },
      createdAt: space.createdAt,
      updatedAt: space.updatedAt,
    }
  }
}

/** The browser's remembered space use (features/space/utils/recentSpaces.ts). */
const RECENT_SPACES_KEY = 'opaa.spaces.recent'

/**
 * Gives a first visit in `scale` a realistic order of use, so the most used spaces are not simply
 * the first ones alphabetically; a remembered order stays untouched.
 */
function seedRecentSpaces(): void {
  try {
    const raw = window.localStorage.getItem(RECENT_SPACES_KEY)
    const stored: unknown = raw ? JSON.parse(raw) : []
    const remembered = Array.isArray(stored) ? stored.filter((id) => typeof id === 'string') : []
    // The column remembers the space of the first route before the list arrives; only an order
    // of a single space counts as a first visit.
    if (remembered.length > 1) return
    const seeded = [
      'space-personal',
      'space-scale-3',
      'space-engineering',
      'space-scale-1',
      'space-phoenix',
      'space-scale-16',
    ]
    const merged = [...remembered, ...seeded.filter((id) => !remembered.includes(id))]
    window.localStorage.setItem(RECENT_SPACES_KEY, JSON.stringify(merged))
  } catch {
    // Storage blocked or unreadable: the list falls back to its own order.
  }
}

/** The spaces `GET /spaces` answers with under the active scenario. */
export function spacesForScenario(): SpaceListResponse[] {
  const scenario = currentMockScenario()
  if (scenario === 'scale') {
    ensureScenarioSpaces()
    seedRecentSpaces()
    return [...mockSpaces, ...scaleSpaces()]
  }
  if (scenario === 'small') {
    return mockSpaces.filter((space) => space.isDefault || space.id === 'space-engineering')
  }
  if (scenario === 'empty') return mockSpaces.filter((space) => space.isDefault)
  return mockSpaces
}
