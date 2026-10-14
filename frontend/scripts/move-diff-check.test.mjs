import { describe, expect, it } from 'vitest'
import { checkChanges, normalize } from './move-diff-check.mjs'

const OLD_PAGE = [
  "import Bell from '../components/notifications/NotificationBell'",
  'import {',
  '  getNotifications,',
  '  markRead,',
  "} from '../services/notificationApi'",
  "vi.mock('../services/notificationApi', async () => {",
  "  const actual = await vi.importActual<typeof import('../services/notificationApi')>(",
  "    '../services/notificationApi',",
  '  )',
  '  return actual',
  '})',
  "const LABELS = ['Speichern', 'Abbrechen']",
  "const Lazy = lazy(() => import('../pages/ChatPage'))",
  '',
].join('\n')

const MOVED_PAGE = [
  "import { Bell } from '@/features/notification'",
  'import {',
  '  getNotifications,',
  '  markRead,',
  "} from '../api/notificationApi'",
  "vi.mock('../api/notificationApi', async () => {",
  "  const actual = await vi.importActual<typeof import('../api/notificationApi')>(",
  "    '../api/notificationApi',",
  '  )',
  '  return actual',
  '})',
  "const LABELS = ['Speichern', 'Abbrechen']",
  "const Lazy = lazy(() => import('@/features/chat/pages/ChatPage'))",
  '',
].join('\n')

function check(oldFiles, newFiles, changes) {
  return checkChanges(
    changes,
    (file) => oldFiles[file],
    (file) => newFiles[file],
  )
}

const rename = (from, to) => ({ status: 'R', from, to })

describe('move-diff-check', () => {
  it('accepts a move that only changes module paths and the import form of a name', () => {
    expect(normalize(OLD_PAGE)).toBe(normalize(MOVED_PAGE))
    expect(
      check(
        {
          'src/pages/Page.tsx': OLD_PAGE,
          'eslint-suppressions.json':
            '{"src/pages/Page.tsx":{"boundaries/dependencies":{"count":1}}}',
        },
        {
          'src/features/notification/pages/Page.tsx': MOVED_PAGE,
          'src/features/notification/index.ts':
            "export { default as Bell } from './components/Bell'\n",
          'src/features/notification/AGENTS.md': '# notification\n',
          'eslint-suppressions.json':
            '{"src/features/notification/pages/Page.tsx":{"boundaries/dependencies":{"count":1}}}',
        },
        [
          rename('src/pages/Page.tsx', 'src/features/notification/pages/Page.tsx'),
          {
            status: 'A',
            from: 'src/features/notification/index.ts',
            to: 'src/features/notification/index.ts',
          },
          {
            status: 'A',
            from: 'src/features/notification/AGENTS.md',
            to: 'src/features/notification/AGENTS.md',
          },
          { status: 'M', from: 'eslint-suppressions.json', to: 'eslint-suppressions.json' },
        ],
      ),
    ).toEqual([])
  })

  it('pairs a deletion and an addition git did not detect as a rename', () => {
    expect(
      check({ 'src/a.ts': OLD_PAGE }, { 'src/features/x/a.ts': MOVED_PAGE }, [
        { status: 'D', from: 'src/a.ts', to: 'src/a.ts' },
        { status: 'A', from: 'src/features/x/a.ts', to: 'src/features/x/a.ts' },
      ]),
    ).toEqual([])
  })

  it.each([
    ['a changed string literal on its own line', "  'Speichern',", "  'Löschen',"],
    ['an added import', '', "import { evil } from '../other/theme'"],
    [
      'code next to vi.importActual',
      "  const a = await vi.importActual('../x')",
      "  const a = await vi.importActual('../y'); doSomethingElse()",
    ],
  ])('reports %s', (_, before, after) => {
    const findings = check(
      { 'src/a.ts': `const x = [\n${before}\n]\n` },
      { 'src/b.ts': `const x = [\n${after}\n]\n` },
      [rename('src/a.ts', 'src/b.ts')],
    )

    expect(findings).toHaveLength(1)
    expect(findings[0]).toContain('src/a.ts -> src/b.ts')
  })

  it('reports other additions, deletions and changed transition-list counts', () => {
    expect(
      check(
        { 'src/gone.ts': 'export const a = 1\n', 'eslint-suppressions.json': '{}' },
        {
          'src/features/x/index.ts': 'export const leaked = 1\n',
          'eslint-suppressions.json': '{"src/a.ts":{"boundaries/dependencies":{"count":1}}}',
        },
        [
          { status: 'D', from: 'src/gone.ts', to: 'src/gone.ts' },
          { status: 'A', from: 'src/features/x/index.ts', to: 'src/features/x/index.ts' },
          { status: 'M', from: 'eslint-suppressions.json', to: 'eslint-suppressions.json' },
        ],
      ),
    ).toEqual([
      'eslint-suppressions.json: entries changed beyond the renamed files',
      'src/features/x/index.ts: added',
      'src/gone.ts: deleted',
    ])
  })
})
