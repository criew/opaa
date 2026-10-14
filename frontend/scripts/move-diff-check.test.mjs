import { describe, expect, it } from 'vitest'
import { checkChanges } from './move-diff-check.mjs'

// A small frontend before and after moving the notification theme.
const SHARED = {
  'src/services/api.ts': 'export const apiClient = 1\n',
  'src/pages/ChatPage.tsx': 'export default function ChatPage() {}\n',
}

const OLD_TREE = {
  ...SHARED,
  'src/components/notifications/NotificationBell.tsx': [
    "import { useState } from 'react'",
    "import { getNotifications } from '../../services/notificationApi'",
    'export default function NotificationBell() {}',
    '',
  ].join('\n'),
  'src/services/notificationApi.ts': [
    "import { apiClient } from './api'",
    'export const getNotifications = 1',
    "export const LABELS = ['Speichern', 'Abbrechen']",
    '',
  ].join('\n'),
  'src/layouts/GlobalRail.tsx': [
    "import NotificationBell from '../components/notifications/NotificationBell'",
    "vi.mock('../services/notificationApi', async () => {",
    "  const actual = await vi.importActual<typeof import('../services/notificationApi')>(",
    "    '../services/notificationApi',",
    '  )',
    '  return actual',
    '})',
    "const Lazy = lazy(() => import('../pages/ChatPage'))",
    '',
  ].join('\n'),
  'eslint-suppressions.json':
    '{"src/services/notificationApi.ts":{"boundaries/dependencies":{"count":1}}}',
}

const NEW_TREE = {
  ...SHARED,
  'src/features/notification/components/NotificationBell.tsx': [
    "import { useState } from 'react'",
    "import { getNotifications } from '../api/notificationApi'",
    'export default function NotificationBell() {}',
    '',
  ].join('\n'),
  'src/features/notification/api/notificationApi.ts': [
    "import { apiClient } from '../../../services/api'",
    'export const getNotifications = 1',
    "export const LABELS = ['Speichern', 'Abbrechen']",
    '',
  ].join('\n'),
  'src/features/notification/index.ts':
    "export { default as NotificationBell } from './components/NotificationBell'\n",
  'src/features/notification/AGENTS.md': '# notification\n',
  'src/layouts/GlobalRail.tsx': [
    "import { NotificationBell } from '@/features/notification'",
    "vi.mock('@/features/notification/api/notificationApi', async () => {",
    "  const actual = await vi.importActual<typeof import('@/features/notification/api/notificationApi')>(",
    "    '@/features/notification/api/notificationApi',",
    '  )',
    '  return actual',
    '})',
    "const Lazy = lazy(() => import('@/pages/ChatPage'))",
    '',
  ].join('\n'),
  'eslint-suppressions.json':
    '{"src/features/notification/api/notificationApi.ts":{"boundaries/dependencies":{"count":1}}}',
}

const BELL_OLD = 'src/components/notifications/NotificationBell.tsx'
const BELL_NEW = 'src/features/notification/components/NotificationBell.tsx'
const API_OLD = 'src/services/notificationApi.ts'
const API_NEW = 'src/features/notification/api/notificationApi.ts'
const RAIL = 'src/layouts/GlobalRail.tsx'
const INDEX = 'src/features/notification/index.ts'

const CHANGES = [
  { status: 'R', from: BELL_OLD, to: BELL_NEW },
  { status: 'R', from: API_OLD, to: API_NEW },
  { status: 'M', from: RAIL, to: RAIL },
  { status: 'A', from: INDEX, to: INDEX },
  {
    status: 'A',
    from: 'src/features/notification/AGENTS.md',
    to: 'src/features/notification/AGENTS.md',
  },
  { status: 'M', from: 'eslint-suppressions.json', to: 'eslint-suppressions.json' },
]

function check(newOverrides = {}, changes = CHANGES, oldTree = OLD_TREE) {
  const newTree = { ...NEW_TREE, ...newOverrides }
  for (const [file, content] of Object.entries(newTree)) if (content === null) delete newTree[file]
  return checkChanges({
    changes,
    oldFiles: Object.keys(oldTree),
    newFiles: Object.keys(newTree),
    readOld: (file) => oldTree[file],
    readNew: (file) => newTree[file],
  })
}

const edit = (file, from, to) => ({ [file]: NEW_TREE[file].replace(from, to) })

describe('move-diff-check', () => {
  it('accepts a move that only changes module paths, also through the new index.ts', () => {
    expect(check()).toEqual([])
  })

  it('pairs a deletion and an addition git did not detect as a rename', () => {
    const changes = CHANGES.filter((c) => c.from !== API_OLD).concat([
      { status: 'D', from: API_OLD, to: API_OLD },
      { status: 'A', from: API_NEW, to: API_NEW },
    ])

    expect(check({}, changes)).toEqual([])
  })

  it.each([
    ['a changed string literal', edit(API_NEW, "'Speichern'", "'Löschen'")],
    [
      'an added import',
      edit(
        API_NEW,
        'export const getNotifications',
        "import { evil } from './evil'\nexport const getNotifications",
      ),
    ],
    [
      'code next to vi.importActual',
      edit(
        RAIL,
        "    '@/features/notification/api/notificationApi',",
        "    '@/features/notification/api/notificationApi'); doSomethingElse((",
      ),
    ],
    ['a renamed binding', edit(BELL_NEW, '{ useState }', '{ useMemo as useState }')],
    ['a changed package', edit(BELL_NEW, "from 'react'", "from 'preact/compat'")],
    [
      'a relative path that now names another module',
      edit(BELL_NEW, "from '../api/notificationApi'", "from '../../../services/api'"),
    ],
    [
      'a vi.mock path that now names another module',
      edit(
        RAIL,
        "vi.mock('@/features/notification/api/notificationApi'",
        "vi.mock('@/services/api'",
      ),
    ],
  ])('reports %s', (_, overrides) => {
    const findings = check(overrides)

    expect(findings).toHaveLength(1)
    expect(findings[0]).toContain(Object.keys(overrides)[0])
  })

  it('reports changes outside frontend/src/ and to non-source files', () => {
    const changes = [
      ...CHANGES,
      { status: 'M', from: '../.github/workflows/ci.yml', to: '../.github/workflows/ci.yml' },
      { status: 'M', from: 'package.json', to: 'package.json' },
      { status: 'M', from: 'eslint/themes.js', to: 'eslint/themes.js' },
    ]

    expect(check({}, changes)).toEqual([
      '../.github/workflows/ci.yml: changed outside frontend/src/',
      'package.json: changed outside frontend/src/',
      'eslint/themes.js: changed outside frontend/src/',
    ])
  })

  it('reports an index.ts that exports a page or holds more than re-exports', () => {
    expect(
      check({
        [INDEX]: `${NEW_TREE[INDEX]}export { default as ChatPage } from '../../pages/ChatPage'\n`,
      }),
    ).toEqual([`${INDEX}: exports the page src/pages/ChatPage.tsx`])
    expect(check({ [INDEX]: `${NEW_TREE[INDEX]}export const leaked = 1\n` })).toEqual([
      `${INDEX}: an index.ts holds re-exports of local modules only`,
      expect.stringContaining(RAIL),
      `${INDEX}: added`,
    ])
  })

  it.each([
    '/* public api */ export const leaked = 1',
    '/** public api */ export const leaked = 1',
    '* export const leaked = 1',
  ])('reports code behind comment markers in an index.ts: %s', (line) => {
    expect(check({ [INDEX]: `// public api\n${NEW_TREE[INDEX]}${line}\n` })).toEqual([
      `${INDEX}: an index.ts holds re-exports of local modules only`,
      expect.stringContaining(RAIL),
      `${INDEX}: added`,
    ])
  })

  it('reports other additions, deletions and changed transition-list counts', () => {
    const changes = [
      ...CHANGES,
      { status: 'A', from: 'src/extra.ts', to: 'src/extra.ts' },
      { status: 'D', from: 'src/services/api.ts', to: 'src/services/api.ts' },
    ]

    expect(
      check(
        {
          'src/extra.ts': 'export const extra = 1\n',
          'eslint-suppressions.json': '{}',
        },
        changes,
      ),
    ).toEqual([
      'eslint-suppressions.json: entries changed beyond the renamed files',
      'src/extra.ts: added',
      'src/services/api.ts: deleted',
    ])
  })
})

describe('move-diff-check with a theme routes.ts', () => {
  const PAGE_OLD = 'src/pages/NotificationsPage.tsx'
  const PAGE_NEW = 'src/features/notification/pages/NotificationsPage.tsx'
  const ARCHIVE_OLD = 'src/pages/NotificationArchivePage.tsx'
  const ARCHIVE_NEW = 'src/features/notification/pages/NotificationArchivePage.tsx'
  const ROUTES = 'src/features/notification/routes.ts'
  const APP = 'src/App.tsx'
  const PAGE = 'export default function NotificationsPage() {}\nexport const helper = 1\n'
  const ARCHIVE = 'export default function NotificationArchivePage() {}\n'
  const PAGE_EXPORTS = [
    "export { default as NotificationsPage } from './pages/NotificationsPage'",
    "export { default as NotificationArchivePage } from './pages/NotificationArchivePage'",
  ]

  const oldTree = {
    ...OLD_TREE,
    [PAGE_OLD]: PAGE,
    [ARCHIVE_OLD]: ARCHIVE,
    [APP]: [
      "import ChatPage from './pages/ChatPage'",
      "import NotificationsPage from './pages/NotificationsPage'",
      "import NotificationArchivePage from './pages/NotificationArchivePage'",
      '',
    ].join('\n'),
  }
  const routes = (...lines) => ({ [ROUTES]: [...lines, ''].join('\n') })
  const newTree = {
    [PAGE_NEW]: PAGE,
    [ARCHIVE_NEW]: ARCHIVE,
    ...routes(...PAGE_EXPORTS),
    [APP]: [
      "import ChatPage from './pages/ChatPage'",
      "import { NotificationsPage, NotificationArchivePage } from '@/features/notification/routes'",
      '',
    ].join('\n'),
  }
  const changes = [
    ...CHANGES,
    { status: 'R', from: PAGE_OLD, to: PAGE_NEW },
    { status: 'R', from: ARCHIVE_OLD, to: ARCHIVE_NEW },
    { status: 'A', from: ROUTES, to: ROUTES },
    { status: 'M', from: APP, to: APP },
  ]
  const checkRoutes = (overrides = {}) => check({ ...newTree, ...overrides }, changes, oldTree)

  it('accepts a routes.ts re-exporting pages of its theme and App.tsx importing them there', () => {
    expect(checkRoutes()).toEqual([])
  })

  it('reports a routes.ts that re-exports a page of another theme', () => {
    expect(
      checkRoutes(
        routes(...PAGE_EXPORTS, "export { default as ChatPage } from '../../pages/ChatPage'"),
      ),
    ).toContain(`${ROUTES}: re-exports src/pages/ChatPage.tsx, no page of its theme`)
  })

  it('reports a routes.ts that re-exports something other than a page', () => {
    expect(
      checkRoutes(
        routes(
          ...PAGE_EXPORTS,
          "export { default as NotificationBell } from './components/NotificationBell'",
        ),
      ),
    ).toContain(
      `${ROUTES}: re-exports src/features/notification/components/NotificationBell.tsx, no page of its theme`,
    )
    expect(
      checkRoutes(routes(...PAGE_EXPORTS, "export { helper } from './pages/NotificationsPage'")),
    ).toContain(`${ROUTES}: re-exports helper of ${PAGE_NEW}, not the page itself`)
  })

  it.each([
    ['plain code', 'export const leaked = 1'],
    ['code after a block comment', '/* routes */ export const leaked = 1'],
    ['code after a comment continuation', '* export const leaked = 1'],
  ])('reports a routes.ts that holds %s next to the re-exports', (_, line) => {
    const findings = checkRoutes(
      routes('/**', ' * Pages of the theme.', ' */', ...PAGE_EXPORTS, line),
    )

    expect(findings).toContain(`${ROUTES}: a routes.ts holds re-exports of local modules only`)
    expect(findings).toContain(`${ROUTES}: added`)
  })

  it('accepts comment-only lines in a routes.ts', () => {
    expect(
      checkRoutes(routes('/**', ' * Pages of the theme.', ' */', '// app only', ...PAGE_EXPORTS)),
    ).toEqual([])
  })

  it('reports an App.tsx import through routes.ts that now names another page', () => {
    const findings = checkRoutes({
      [APP]: [
        "import ChatPage from './pages/ChatPage'",
        "import { NotificationArchivePage as NotificationsPage, NotificationArchivePage } from '@/features/notification/routes'",
        '',
      ].join('\n'),
    })

    expect(findings).toHaveLength(1)
    expect(findings[0]).toContain(APP)
  })
})
