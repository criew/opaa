import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { ESLint } from 'eslint'
import tseslint from 'typescript-eslint'
import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { themeBoundaries } from './boundaries.js'

// A throwaway frontend with files in the theme layout, linted with the real rule.
const FIXTURE = {
  'tsconfig.app.json': JSON.stringify({
    compilerOptions: { moduleResolution: 'bundler', paths: { '@/*': ['./src/*'] } },
    include: ['src'],
  }),
  'src/shared/session/authStore.ts': 'export const authStore = 1\n',
  'src/shared/mocks/authFixtures.ts': 'export const mockAuth = {}\n',
  'src/features/space/index.ts': [
    "export { SpaceTile } from './components/SpaceTile'",
    "export { spaceStore } from './stores/spaceStore'",
    '',
  ].join('\n'),
  'src/features/space/components/SpaceTile.ts': 'export const SpaceTile = 1\n',
  'src/features/space/stores/spaceStore.ts': 'export const spaceStore = 1\n',
  'src/features/space/mocks/spaceFixtures.ts': 'export const mockSpaces = []\n',
  'src/features/library/index.ts': "export { libraryStore } from './stores/libraryStore'\n",
  'src/features/library/stores/libraryStore.ts': 'export const libraryStore = 1\n',
  'src/features/model/mocks/modelFixtures.ts': 'export const mockModels = []\n',
}

let root
let eslint

beforeAll(() => {
  root = fs.mkdtempSync(path.join(os.tmpdir(), 'theme-boundaries-'))
  for (const [file, content] of Object.entries(FIXTURE)) {
    fs.mkdirSync(path.dirname(path.join(root, file)), { recursive: true })
    fs.writeFileSync(path.join(root, file), content)
  }
  eslint = new ESLint({
    cwd: root,
    overrideConfigFile: true,
    overrideConfig: [
      { files: ['**/*.{ts,tsx}'], languageOptions: { parser: tseslint.parser } },
      themeBoundaries(root),
    ],
  })
})

afterAll(() => {
  fs.rmSync(root, { recursive: true, force: true })
})

async function violations(file, code) {
  const [result] = await eslint.lintText(code, { filePath: path.join(root, file) })
  expect(result.messages.filter((m) => m.fatal)).toEqual([])
  return result.messages.map((m) => m.ruleId)
}

describe('theme boundaries', () => {
  it('rejects a new dependency between themes', async () => {
    expect(
      await violations(
        'src/features/space/pages/SpacePage.tsx',
        "import { libraryStore } from '../../library/stores/libraryStore'",
      ),
    ).toEqual(['boundaries/dependencies'])
  })

  it('rejects it through the @/ alias, in vi.mock() and from shared code', async () => {
    expect(
      await violations(
        'src/features/space/pages/SpacePage.tsx',
        "import { libraryStore } from '@/features/library'",
      ),
    ).toEqual(['boundaries/dependencies'])
    expect(
      await violations(
        'src/features/space/pages/SpacePage.test.tsx',
        "vi.mock('@/features/library/stores/libraryStore')",
      ),
    ).toEqual(['boundaries/dependencies'])
    expect(
      await violations(
        'src/shared/stores/uiStore.ts',
        "import { spaceStore } from '@/features/space'",
      ),
    ).toEqual(['boundaries/dependencies'])
  })

  it('allows the theme itself, shared code and the allowed themes', async () => {
    expect(
      await violations(
        'src/features/chat/pages/ChatPage.tsx',
        [
          "import { authStore } from '@/shared/session/authStore'",
          "import { spaceStore } from '@/features/space'",
          "import { libraryStore } from '@/features/library'",
          "import { useState } from 'react'",
        ].join('\n'),
      ),
    ).toEqual([])
    expect(
      await violations(
        'src/features/space/stores/spaceStore.test.ts',
        "import { spaceStore } from './spaceStore'",
      ),
    ).toEqual([])
  })

  it('reaches a theme from outside only through its index.ts', async () => {
    expect(
      await violations(
        'src/features/chat/pages/ChatPage.tsx',
        "import { SpaceTile } from '@/features/space'",
      ),
    ).toEqual([])
    expect(
      await violations(
        'src/features/chat/pages/ChatPage.tsx',
        "import { SpaceTile } from '@/features/space/components/SpaceTile'",
      ),
    ).toEqual(['boundaries/dependencies'])
    expect(
      await violations(
        'src/features/space/pages/SpacePage.tsx',
        "import { SpaceTile } from '../components/SpaceTile'",
      ),
    ).toEqual([])
  })

  it('lets a test vi.mock() a module behind the index.ts of an allowed theme only', async () => {
    const mockSpaceTile = "vi.mock('@/features/space/components/SpaceTile')"

    expect(await violations('src/features/chat/pages/ChatPage.test.tsx', mockSpaceTile)).toEqual([])
    expect(
      await violations('src/features/library/pages/LibraryDetailPage.test.tsx', mockSpaceTile),
    ).toEqual(['boundaries/dependencies'])
    expect(
      await violations(
        'src/features/chat/pages/ChatPage.test.tsx',
        "import { SpaceTile } from '@/features/space/components/SpaceTile'",
      ),
    ).toEqual(['boundaries/dependencies'])
  })

  it('lets tests use the fixtures of every theme, but not production code', async () => {
    expect(
      await violations(
        'src/features/library/pages/LibraryDetailPage.test.tsx',
        "import { mockSpaces } from '@/features/space/mocks/spaceFixtures'",
      ),
    ).toEqual([])
    expect(
      await violations(
        'src/features/library/pages/LibraryDetailPage.tsx',
        "import { mockSpaces } from '@/features/space/mocks/spaceFixtures'",
      ),
    ).toEqual(['boundaries/dependencies'])
  })

  it('keeps mocks out of production code, even those of the own or an allowed theme', async () => {
    // own theme
    expect(
      await violations(
        'src/features/model/components/ModelList.tsx',
        "import { mockModels } from '../mocks/modelFixtures'",
      ),
    ).toEqual(['boundaries/dependencies'])
    // allowed theme (chat -> space)
    expect(
      await violations(
        'src/features/chat/pages/ChatPage.tsx',
        "import { mockSpaces } from '@/features/space/mocks/spaceFixtures'",
      ),
    ).toEqual(['boundaries/dependencies'])
    // shared mocks, from shared and from a theme
    expect(
      await violations(
        'src/shared/theme/theme.ts',
        "import { mockAuth } from '../mocks/authFixtures'",
      ),
    ).toEqual(['boundaries/dependencies'])
    expect(
      await violations(
        'src/features/model/components/ModelList.tsx',
        "import { mockAuth } from '@/shared/mocks/authFixtures'",
      ),
    ).toEqual(['boundaries/dependencies'])
    // tests, mocks and the MSW wiring in app keep their access
    expect(
      await violations(
        'src/features/model/components/ModelList.test.tsx',
        "import { mockModels } from '../mocks/modelFixtures'",
      ),
    ).toEqual([])
    expect(
      await violations(
        'src/features/model/mocks/modelHandlers.ts',
        "import { mockModels } from './modelFixtures'",
      ),
    ).toEqual([])
    expect(
      await violations(
        'src/app/mocks/handlers.ts',
        "import { mockModels } from '@/features/model/mocks/modelFixtures'",
      ),
    ).toEqual([])
  })
})
