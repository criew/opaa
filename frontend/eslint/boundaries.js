// ESLint configuration of the theme boundaries, generated from eslint/themes.js.
//
// Every file gets file categories (eslint-plugin-boundaries `boundaries/files`): its bucket kind
// (`app`, `shared`, `feature`, `feature-mocks`, `test-infra`), for themes also `theme:<name>`, and
// `test` for test files. The single rule `boundaries/dependencies` disallows every local
// dependency that no policy allows; policies are evaluated last-match-wins.
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import boundaries from 'eslint-plugin-boundaries'
import {
  ALLOWED,
  APP,
  MOCK_GLOBS,
  SHARED,
  TEST_INFRA,
  THEMES,
  bucketGlobs,
  featureRoot,
  themeGlobs,
} from './themes.js'

const TEST_GLOBS = ['src/**/*.test.{ts,tsx}']
const theme = (name) => `theme:${name}`
const themeNames = Object.keys(THEMES)

const files = [
  ...themeNames.flatMap((name) => [
    { category: theme(name), pattern: themeGlobs(name) },
    { category: 'feature', pattern: themeGlobs(name) },
  ]),
  { category: 'feature-mocks', pattern: MOCK_GLOBS },
  { category: 'shared', pattern: bucketGlobs(SHARED) },
  { category: 'app', pattern: bucketGlobs(APP) },
  { category: 'test-infra', pattern: bucketGlobs(TEST_INFRA) },
  { category: 'test', pattern: TEST_GLOBS },
  { category: 'entry', pattern: 'src/features/*/index.ts' },
  { category: 'routes', pattern: 'src/features/*/routes.ts' },
]

const VITEST_MOCK = 'vitest-mock'
const dependentsOf = (name) => themeNames.filter((other) => ALLOWED[other].includes(name))
const fromCategory = (category) => ({ file: { categories: category } })
const toCategories = (categories) => ({ to: { file: { categories: { anyOf: categories } } } })

const policies = [
  // npm packages and Node built-ins are out of scope; this rule only guards local files.
  { allow: { to: { module: { origin: ['external', 'core'] } } } },
  { from: fromCategory('shared'), allow: toCategories(['shared']) },
  { from: fromCategory('app'), allow: { to: { module: { origin: 'local' } } } },
  { from: fromCategory('test-infra'), allow: { to: { module: { origin: 'local' } } } },
  ...themeNames.map((name) => ({
    from: fromCategory(theme(name)),
    allow: toCategories([theme(name), 'shared', ...ALLOWED[name].map(theme)]),
  })),
  // Tests and mock handlers share the simulated backend: fixtures of every theme are reachable.
  { from: fromCategory('test'), allow: toCategories(['test-infra', 'feature-mocks']) },
  { from: fromCategory('feature-mocks'), allow: toCategories(['feature-mocks']) },
  // A moved theme is reached from outside only through its index.ts (app: also routes.ts);
  // its mocks stay reachable for tests and the MSW wiring.
  ...themeNames
    .filter((name) => dependentsOf(name).length > 0)
    .map((name) => ({
      from: { file: { categories: { anyOf: dependentsOf(name).map(theme) } } },
      disallow: {
        to: {
          file: {
            path: `${featureRoot(name)}/**`,
            categories: { noneOf: ['entry', 'feature-mocks'] },
          },
        },
      },
      message: `Theme "${name}" is reachable from outside only through ${featureRoot(name)}/index.ts.`,
    })),
  ...themeNames.map((name) => ({
    from: { file: { categories: { anyOf: ['app', 'test-infra'] } } },
    disallow: {
      to: {
        file: {
          path: `${featureRoot(name)}/**`,
          categories: { noneOf: ['entry', 'routes', 'feature-mocks'] },
        },
      },
    },
    message: `Theme "${name}" is reachable from app and test setup only through index.ts and routes.ts.`,
  })),
  // vi.mock() may replace a single module behind an allowed theme's index.ts: consumers that
  // import through the index see the mock as well.
  ...themeNames
    .filter((name) => ALLOWED[name].length > 0)
    .map((name) => ({
      from: { file: { categories: { allOf: ['test', theme(name)] } } },
      allow: {
        ...toCategories(ALLOWED[name].map(theme)),
        dependency: { nodeKind: VITEST_MOCK },
      },
    })),
  // Fixtures and handlers never reach production code; app keeps main.tsx -> mocks/browser.
  {
    from: { file: { categories: { noneOf: ['test', 'feature-mocks', 'test-infra', 'app'] } } },
    disallow: toCategories(['feature-mocks']),
    message: 'Mock handlers and fixtures are reachable from tests, mocks and app only.',
  },
]

/**
 * The flat-config block for a frontend rooted at `rootPath`; globs in themes.js are relative to it,
 * whatever directory ESLint runs from.
 */
export function themeBoundaries(rootPath) {
  return {
    files: ['src/**/*.{ts,tsx}'],
    plugins: { boundaries },
    settings: {
      'import/resolver': {
        typescript: { project: path.join(rootPath, 'tsconfig.app.json') },
      },
      'boundaries/root-path': rootPath,
      'boundaries/files': files,
      'boundaries/additional-dependency-nodes': [
        // vi.mock()/vi.importActual() name a module path just like an import does.
        {
          selector:
            'CallExpression[callee.object.name=vi][callee.property.name=/^(mock|doMock|importActual)$/] > Literal:first-child',
          kind: 'value',
          name: VITEST_MOCK,
        },
      ],
    },
    rules: {
      'boundaries/dependencies': [
        'error',
        {
          default: 'disallow',
          policies,
        },
      ],
    },
  }
}

export default themeBoundaries(path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..'))
