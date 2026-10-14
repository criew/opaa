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
  it('accepts a move that only changes module paths, also through the new index.ts', async () => {
    expect(await check()).toEqual([])
  })

  it('pairs a deletion and an addition git did not detect as a rename', async () => {
    const changes = CHANGES.filter((c) => c.from !== API_OLD).concat([
      { status: 'D', from: API_OLD, to: API_OLD },
      { status: 'A', from: API_NEW, to: API_NEW },
    ])

    expect(await check({}, changes)).toEqual([])
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
  ])('reports %s', async (_, overrides) => {
    const findings = await check(overrides)

    expect(findings).toHaveLength(1)
    expect(findings[0]).toContain(Object.keys(overrides)[0])
  })

  it('reports changes outside frontend/src/ and to non-source files', async () => {
    const changes = [
      ...CHANGES,
      { status: 'M', from: '../.github/workflows/ci.yml', to: '../.github/workflows/ci.yml' },
      { status: 'M', from: 'package.json', to: 'package.json' },
      { status: 'M', from: 'eslint/themes.js', to: 'eslint/themes.js' },
    ]

    expect(await check({}, changes)).toEqual([
      '../.github/workflows/ci.yml: changed outside frontend/src/',
      'package.json: changed outside frontend/src/',
      'eslint/themes.js: changed outside frontend/src/',
    ])
  })

  it('reports an index.ts that exports a page or holds more than re-exports', async () => {
    expect(
      await check({
        [INDEX]: `${NEW_TREE[INDEX]}export { default as ChatPage } from '../../pages/ChatPage'\n`,
      }),
    ).toEqual([`${INDEX}: exports the page src/pages/ChatPage.tsx`])
    expect(await check({ [INDEX]: `${NEW_TREE[INDEX]}export const leaked = 1\n` })).toEqual([
      `${INDEX}: an index.ts holds re-exports of local modules only`,
      expect.stringContaining(RAIL),
      `${INDEX}: added`,
    ])
  })

  it.each([
    '/* public api */ export const leaked = 1',
    '/** public api */ export const leaked = 1',
    '* export const leaked = 1',
  ])('reports code behind comment markers in an index.ts: %s', async (line) => {
    expect(await check({ [INDEX]: `// public api\n${NEW_TREE[INDEX]}${line}\n` })).toEqual([
      `${INDEX}: an index.ts holds re-exports of local modules only`,
      expect.stringContaining(RAIL),
      `${INDEX}: added`,
    ])
  })

  it('reports other additions, deletions and changed transition-list counts', async () => {
    const changes = [
      ...CHANGES,
      { status: 'A', from: 'src/extra.ts', to: 'src/extra.ts' },
      { status: 'D', from: 'src/services/api.ts', to: 'src/services/api.ts' },
    ]

    expect(
      await check(
        {
          'src/extra.ts': 'export const extra = 1\n',
          'eslint-suppressions.json':
            '{"src/features/notification/api/notificationApi.ts":{"boundaries/dependencies":{"count":2}}}',
        },
        changes,
      ),
    ).toEqual([
      'eslint-suppressions.json: src/features/notification/api/notificationApi.ts boundaries/dependencies grows from 1 to 2',
      'src/extra.ts: added',
      'src/services/api.ts: deleted',
    ])
  })
})

describe('move-diff-check with a shrinking transition list', () => {
  const SUPPRESSIONS = 'eslint-suppressions.json'
  const oldTree = {
    ...OLD_TREE,
    [SUPPRESSIONS]: JSON.stringify({
      'src/services/notificationApi.ts': { 'boundaries/dependencies': { count: 2 } },
      'src/layouts/GlobalRail.tsx': { 'boundaries/dependencies': { count: 1 } },
    }),
  }
  const checkList = (list) =>
    check({ [SUPPRESSIONS]: JSON.stringify(list, null, 2) }, CHANGES, oldTree)

  it('accepts removed entries and lower counts next to the renames', async () => {
    expect(
      await checkList({
        'src/features/notification/api/notificationApi.ts': {
          'boundaries/dependencies': { count: 1 },
        },
      }),
    ).toEqual([])
    expect(await checkList({})).toEqual([])
  })

  it.each([
    [
      'a higher count',
      {
        'src/features/notification/api/notificationApi.ts': {
          'boundaries/dependencies': { count: 3 },
        },
      },
      `${SUPPRESSIONS}: src/features/notification/api/notificationApi.ts boundaries/dependencies grows from 2 to 3`,
    ],
    [
      'a new file',
      { 'src/pages/ChatPage.tsx': { 'boundaries/dependencies': { count: 1 } } },
      `${SUPPRESSIONS}: src/pages/ChatPage.tsx boundaries/dependencies grows from 0 to 1`,
    ],
    [
      'a new rule',
      { 'src/layouts/GlobalRail.tsx': { 'no-console': { count: 1 } } },
      `${SUPPRESSIONS}: src/layouts/GlobalRail.tsx no-console grows from 0 to 1`,
    ],
    [
      'an entry under its old name',
      { 'src/services/notificationApi.ts': { 'boundaries/dependencies': { count: 1 } } },
      `${SUPPRESSIONS}: src/services/notificationApi.ts boundaries/dependencies grows from 0 to 1`,
    ],
  ])('reports %s', async (_, list, finding) => {
    expect(await checkList(list)).toEqual([finding])
  })
})

describe('move-diff-check with Prettier re-wrapping changed module paths', () => {
  const rail = (...lines) => ({
    [RAIL]: [
      "import { NotificationBell } from '@/features/notification'",
      ...lines,
      "const Lazy = lazy(() => import('@/pages/ChatPage'))",
      '',
    ].join('\n'),
  })
  const PATH = "'@/features/notification/api/notificationApi'"
  const BODY = ['  return actual', '})']

  it.each([
    [
      'after the assignment',
      [
        `vi.mock(${PATH}, async () => {`,
        '  const actual =',
        `    await vi.importActual<typeof import(${PATH})>(${PATH})`,
        ...BODY,
      ],
    ],
    [
      'inside the type argument',
      [
        `vi.mock(${PATH}, async () => {`,
        '  const actual = await vi.importActual<',
        `    typeof import(${PATH})`,
        `  >(${PATH})`,
        ...BODY,
      ],
    ],
    [
      'inside the arguments with a trailing comma',
      [
        'vi.mock(',
        `  ${PATH},`,
        '  async () => {',
        `    const actual = await vi.importActual<typeof import(${PATH})>(${PATH})`,
        '    return actual',
        '  },',
        ')',
      ],
    ],
  ])('accepts a call wrapped %s', async (_, lines) => {
    expect(await check(rail(...lines))).toEqual([])
  })

  it.each([
    [
      'code appended in a wrapped line',
      [
        `vi.mock(${PATH}, async () => {`,
        '  const actual =',
        `    await vi.importActual<typeof import(${PATH})>(${PATH}) && evil()`,
        ...BODY,
      ],
    ],
    [
      'another module in a wrapped line',
      [
        `vi.mock(${PATH}, async () => {`,
        '  const actual =',
        "    await vi.importActual<typeof import('@/services/api')>('@/services/api')",
        ...BODY,
      ],
    ],
    [
      'a line break that changes the meaning',
      [
        `vi.mock(${PATH}, async () => {`,
        `  const actual = await vi.importActual<typeof import(${PATH})>(${PATH})`,
        '  return',
        '  actual',
        '})',
      ],
    ],
  ])('reports %s', async (_, lines) => {
    const findings = await check(rail(...lines))

    expect(findings).toHaveLength(1)
    expect(findings[0]).toContain(RAIL)
  })
})

describe('move-diff-check with an index.ts that existed before the move', () => {
  const LABELS = 'src/features/notification/utils/labels.ts'
  const OTHER = 'src/layouts/Other.tsx'
  const OLD_INDEX = "export { label, type Label } from './utils/labels'\n"
  const oldTree = {
    ...OLD_TREE,
    [LABELS]: 'export const label = 1\nexport type Label = string\n',
    [INDEX]: OLD_INDEX,
    [OTHER]: "import { label } from '@/features/notification'\n",
  }
  const newIndex = (...lines) => ({ [INDEX]: [...lines, ''].join('\n') })
  const GROWN = [
    'export {',
    '  label,',
    '  type Label,',
    "} from './utils/labels'",
    "export { default as NotificationBell } from './components/NotificationBell'",
  ]
  const changes = CHANGES.map((c) => (c.to === INDEX ? { ...c, status: 'M' } : c))
  const checkIndex = (overrides) =>
    check(
      {
        [LABELS]: oldTree[LABELS],
        [OTHER]: oldTree[OTHER],
        ...newIndex(...GROWN),
        ...overrides,
      },
      changes,
      oldTree,
    )

  it('accepts added re-exports and resolves imports through it on both sides', async () => {
    expect(await checkIndex({})).toEqual([])
  })

  it('accepts a caller switching from the module to the index for an existing re-export', async () => {
    const oldWithDeepImport = {
      ...oldTree,
      [OTHER]: "import { label } from '../features/notification/utils/labels'\n",
    }

    expect(
      await check(
        { [LABELS]: oldTree[LABELS], [OTHER]: oldTree[OTHER], ...newIndex(...GROWN) },
        [...changes, { status: 'M', from: OTHER, to: OTHER }],
        oldWithDeepImport,
      ),
    ).toEqual([])
  })

  it('reports code smuggled into it', async () => {
    expect(await checkIndex(newIndex(...GROWN, 'export const leaked = 1'))).toContain(
      `${INDEX}: an index.ts holds re-exports of local modules only`,
    )
  })

  it('reports a page it newly exports', async () => {
    expect(
      await checkIndex(
        newIndex(...GROWN, "export { default as ChatPage } from '../../pages/ChatPage'"),
      ),
    ).toEqual([`${INDEX}: exports the page src/pages/ChatPage.tsx`])
  })

  it.each([
    [
      'removed',
      newIndex("export { default as NotificationBell } from './components/NotificationBell'"),
      [`${INDEX}: removes the re-export label`, `${INDEX}: removes the re-export Label`],
    ],
    [
      'pointed at another module',
      newIndex(
        "export { type Label } from './utils/labels'",
        "export { default as NotificationBell, default as label } from './components/NotificationBell'",
      ),
      [`${INDEX}: changes the re-export label`],
    ],
    [
      'pointed at another export',
      newIndex(
        "export { type Label, type Label as label } from './utils/labels'",
        "export { default as NotificationBell } from './components/NotificationBell'",
      ),
      [`${INDEX}: changes the re-export label`],
    ],
  ])('reports a re-export %s', async (_, overrides, expected) => {
    const findings = await checkIndex(overrides)

    for (const finding of expected) expect(findings).toContain(finding)
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

  it('accepts a routes.ts re-exporting pages of its theme and App.tsx importing them there', async () => {
    expect(await checkRoutes()).toEqual([])
  })

  it('reports a routes.ts that re-exports a page of another theme', async () => {
    expect(
      await checkRoutes(
        routes(...PAGE_EXPORTS, "export { default as ChatPage } from '../../pages/ChatPage'"),
      ),
    ).toContain(`${ROUTES}: re-exports src/pages/ChatPage.tsx, no page of its theme`)
  })

  it('reports a routes.ts that re-exports something other than a page', async () => {
    expect(
      await checkRoutes(
        routes(
          ...PAGE_EXPORTS,
          "export { default as NotificationBell } from './components/NotificationBell'",
        ),
      ),
    ).toContain(
      `${ROUTES}: re-exports src/features/notification/components/NotificationBell.tsx, no page of its theme`,
    )
    expect(
      await checkRoutes(
        routes(...PAGE_EXPORTS, "export { helper } from './pages/NotificationsPage'"),
      ),
    ).toContain(`${ROUTES}: re-exports helper of ${PAGE_NEW}, not the page itself`)
  })

  it.each([
    ['plain code', 'export const leaked = 1'],
    ['code after a block comment', '/* routes */ export const leaked = 1'],
    ['code after a comment continuation', '* export const leaked = 1'],
  ])('reports a routes.ts that holds %s next to the re-exports', async (_, line) => {
    const findings = await checkRoutes(
      routes('/**', ' * Pages of the theme.', ' */', ...PAGE_EXPORTS, line),
    )

    expect(findings).toContain(`${ROUTES}: a routes.ts holds re-exports of local modules only`)
    expect(findings).toContain(`${ROUTES}: added`)
  })

  it('accepts comment-only lines in a routes.ts', async () => {
    expect(
      await checkRoutes(
        routes('/**', ' * Pages of the theme.', ' */', '// app only', ...PAGE_EXPORTS),
      ),
    ).toEqual([])
  })

  it('reports an App.tsx import through routes.ts that now names another page', async () => {
    const findings = await checkRoutes({
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

describe('move-diff-check with layout-sensitive code in a moved file', () => {
  const FROM = 'src/components/probe/Probe.tsx'
  const TO = 'src/features/notification/components/Probe.tsx'
  const probe = (before, after) =>
    check({ [TO]: `${after}\n` }, [...CHANGES, { status: 'R', from: FROM, to: TO }], {
      ...OLD_TREE,
      [FROM]: `${before}\n`,
    })
  const jsx = (text) => `export const P = () => <p>${text}</p>`

  it('accepts an unchanged file', async () => {
    expect(await probe(jsx('a, b'), jsx('a, b'))).toEqual([])
  })

  it.each([
    ['a decrement next to a minus', 'export const r = a - --b', 'export const r = a---b'],
    ['an increment next to a plus', 'export const r = a + ++b', 'export const r = a+++b'],
    ['spaces in a regex', 'export const re = / x /', 'export const re = /x/'],
    ['a space in a regex class', 'export const re = /[ ]/', 'export const re = /[]/'],
    ['JSX text before a comma', jsx('a, b'), jsx('a , b')],
    ['JSX text with umlauts', jsx('Größe'), jsx('Grö ße')],
    ['JSX text after an expression', jsx('{a} b'), jsx('{a}b')],
    ['JSX text with a hyphen', jsx('E-Mail'), jsx('E - Mail')],
    ['JSX text with an entity', jsx('a &gt; b'), jsx('a&gt;b')],
    ['an array hole', 'export const holes = [,]', 'export const holes = []'],
    ['a minus turned into a decrement', 'export const r = a - -b', 'export const r = a--b'],
    ['a split number', 'export const n = 1.5', 'export const n = 1 . 5'],
  ])('reports %s', async (_, before, after) => {
    const findings = await probe(before, after)

    expect(findings).toHaveLength(1)
    expect(findings[0]).toContain(TO)
  })
})

describe('move-diff-check with export * in an existing index.ts', () => {
  const LABELS = 'src/features/notification/utils/labels.ts'
  const MORE = 'src/features/notification/utils/more.ts'
  const OTHER = 'src/layouts/Other.tsx'
  const unchanged = {
    [LABELS]: 'export const label = 1\n',
    [MORE]: 'export const more = 1\n',
    [OTHER]: "import { label } from '@/features/notification'\n",
  }
  const oldTree = {
    ...OLD_TREE,
    ...unchanged,
    [INDEX]: "export * from './utils/labels'\nexport * from './utils/more'\n",
  }
  const changes = CHANGES.map((c) => (c.to === INDEX ? { ...c, status: 'M' } : c))
  const checkStars = (...lines) =>
    check(
      {
        ...unchanged,
        [INDEX]: [...lines, ''].join('\n'),
        // the bell stays out of the index, so that only the stars are under test
        [RAIL]: NEW_TREE[RAIL].replace(
          "import { NotificationBell } from '@/features/notification'",
          "import NotificationBell from '@/features/notification/components/NotificationBell'",
        ),
      },
      changes,
      oldTree,
    )

  it('accepts reordered stars with callers through the index', async () => {
    expect(
      await checkStars("export * from './utils/more'", "export * from './utils/labels'"),
    ).toEqual([])
  })

  it('reports a dropped export *', async () => {
    expect(await checkStars("export * from './utils/labels'")).toContain(
      `${INDEX}: removes the re-export * from ${MORE}`,
    )
  })

  it('reports a named re-export that may hide an export of an existing export *', async () => {
    expect(
      await checkStars(
        "export * from './utils/labels'",
        "export * from './utils/more'",
        "export { default as label } from './components/NotificationBell'",
      ),
    ).toContain(`${INDEX}: label may change what an existing export * provides`)
  })
})
