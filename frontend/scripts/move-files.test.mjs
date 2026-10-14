import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { moveFiles, parseMoves } from './move-files.mjs'

const FILES = {
  'src/stores/libraryStore.ts': "import { getLibraries } from '../services/libraryApi'\n",
  'src/services/libraryApi.ts':
    "import { apiClient } from './api'\nexport const getLibraries = 1\n",
  'src/services/api.ts': 'export const apiClient = 1\n',
  'src/components/library/LibraryList.tsx': [
    "import { useLibraryStore } from '../../stores/libraryStore'",
    "import Icon from './sources'",
    "import type { Settings } from '@/services/libraryApi'",
    "import './library.css'",
    '',
  ].join('\n'),
  'src/components/library/library.css': '.a {}\n',
  'src/components/library/sources/index.ts': "export { default } from '../../../services/api'\n",
  'src/pages/LibraryPage.test.tsx': [
    "import LibraryList from '../components/library/LibraryList'",
    "vi.mock('../services/libraryApi', async () => {",
    "  const actual = await vi.importActual<typeof import('../services/libraryApi')>(",
    "    '../services/libraryApi',",
    '  )',
    '  return actual',
    '})',
    "const pages = import.meta.glob('../components/library/*.tsx')",
    '',
  ].join('\n'),
  'eslint-suppressions.json': JSON.stringify(
    { 'src/stores/libraryStore.ts': { 'boundaries/dependencies': { count: 1 } } },
    null,
    2,
  ),
}

let root

function read(file) {
  return fs.readFileSync(path.join(root, file), 'utf8')
}

beforeEach(() => {
  root = fs.mkdtempSync(path.join(os.tmpdir(), 'move-files-'))
  for (const [file, content] of Object.entries(FILES)) {
    fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true })
    fs.writeFileSync(path.join(root, file), content)
  }
  const git = (...args) =>
    execFileSync('git', ['-c', 'core.autocrlf=false', ...args], { cwd: root })
  git('init', '-q')
  git('add', '.')
  git('-c', 'user.name=test', '-c', 'user.email=test@example.invalid', 'commit', '-qm', 'init')
})

afterEach(() => {
  fs.rmSync(root, { recursive: true, force: true })
})

const MOVES = [
  ['src/components/library', 'src/features/library/components'],
  ['src/stores/libraryStore.ts', 'src/features/library/stores/libraryStore.ts'],
  ['src/services/libraryApi.ts', 'src/features/library/api/libraryApi.ts'],
]

describe('move-files', () => {
  it('parses a moves file and skips comments and blank lines', () => {
    expect(parseMoves('# comment\n\nsrc/a.ts src/b.ts\r\n  src/c  src/d  \n')).toEqual([
      ['src/a.ts', 'src/b.ts'],
      ['src/c', 'src/d'],
    ])
    expect(() => parseMoves('src/a.ts')).toThrow()
  })

  it('moves files and folders with git mv and rewrites every path pointing at them', () => {
    const { globWarnings } = moveFiles(root, MOVES, { log: () => {} })

    expect(fs.existsSync(path.join(root, 'src/components/library'))).toBe(false)
    const status = execFileSync('git', ['status', '--porcelain'], { cwd: root, encoding: 'utf8' })
    expect(status).toMatch(
      /^R. src\/stores\/libraryStore\.ts -> src\/features\/library\/stores\/libraryStore\.ts$/m,
    )

    // the moved file's own imports, relative to its new place
    expect(read('src/features/library/stores/libraryStore.ts')).toBe(
      "import { getLibraries } from '../api/libraryApi'\n",
    )
    expect(read('src/features/library/api/libraryApi.ts')).toContain(
      "import { apiClient } from '../../../services/api'",
    )
    // index imports, @/ paths and side-effect imports keep their form
    expect(read('src/features/library/components/LibraryList.tsx')).toBe(
      [
        "import { useLibraryStore } from '../stores/libraryStore'",
        "import Icon from './sources'",
        "import type { Settings } from '@/features/library/api/libraryApi'",
        "import './library.css'",
        '',
      ].join('\n'),
    )
    expect(read('src/features/library/components/sources/index.ts')).toBe(
      "export { default } from '../../../../services/api'\n",
    )
    // a file that stays: imports, vi.mock and vi.importActual, also inside the type argument
    expect(read('src/pages/LibraryPage.test.tsx')).toBe(
      [
        "import LibraryList from '../features/library/components/LibraryList'",
        "vi.mock('../features/library/api/libraryApi', async () => {",
        "  const actual = await vi.importActual<typeof import('../features/library/api/libraryApi')>(",
        "    '../features/library/api/libraryApi',",
        '  )',
        '  return actual',
        '})',
        "const pages = import.meta.glob('../components/library/*.tsx')",
        '',
      ].join('\n'),
    )
    expect(globWarnings).toEqual([
      "src/pages/LibraryPage.test.tsx: import.meta.glob('../components/library/*.tsx')",
    ])
    expect(JSON.parse(read('eslint-suppressions.json'))).toEqual({
      'src/features/library/stores/libraryStore.ts': { 'boundaries/dependencies': { count: 1 } },
    })
  })

  it('changes nothing on a dry run', () => {
    const { rewrites } = moveFiles(root, MOVES, { dryRun: true, log: () => {} })

    expect(rewrites.size).toBe(5)
    for (const [file, content] of Object.entries(FILES)) expect(read(file)).toBe(content)
  })

  it('refuses a target that already exists', () => {
    expect(() =>
      moveFiles(root, [['src/services/api.ts', 'src/stores/libraryStore.ts']], { log: () => {} }),
    ).toThrow('Target already exists')
  })
})
