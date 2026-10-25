// Theme boundaries of the frontend, source of eslint/boundaries.js (rules in frontend/AGENTS.md).
// Every file under src/ belongs to exactly one bucket - a theme, shared, app or test-infra - by its
// location (eslint/themes.test.mjs). Globs are micromatch patterns relative to frontend/.

/** Location of a theme; the theme's public entry is `index.ts` directly below it. */
export const featureRoot = (theme) => `src/features/${theme}`

/** Mock handlers and fixtures: the MSW wiring in app, the shared and the per-theme mocks. */
export const MOCK_GLOBS = ['src/{app,shared}/mocks/**', 'src/features/*/mocks/**']

/** The themes, named after the tags of their operations in the OpenAPI spec. */
export const THEMES = [
  'chat',
  'space',
  'asset',
  'library',
  'prompt',
  'principal',
  'group',
  'succession',
  'capability',
  'user',
  'identity-provider',
  'directory-sync',
  'model',
  'mail',
  'branding',
  'connection',
  'external-access',
  'search-admin',
  'audit',
  'notification',
  'compliance',
  'auth',
  'link-targets',
  'settings',
  'installation',
]

/** Cross-cutting building blocks; may depend on nothing but itself. */
export const SHARED = 'src/shared'

/** Application frame: routing, shell, area pages composed from several themes, MSW wiring. */
export const APP = 'src/app'

/** Test setup and render helpers; may depend on everything, nothing but tests depends on it. */
export const TEST_INFRA = 'src/test'

/**
 * Allowed dependencies between themes, cycle-free. A theme always reaches itself and `shared`;
 * `app` and `test-infra` reach every theme. Edges point from the depending theme to its targets.
 */
export const ALLOWED = {
  chat: ['space', 'prompt', 'library'],
  space: ['asset', 'principal', 'succession'],
  asset: ['principal', 'succession'],
  library: ['asset', 'principal', 'succession', 'connection', 'external-access'],
  prompt: ['asset', 'principal', 'succession'],
  principal: [],
  group: ['principal', 'succession', 'identity-provider', 'directory-sync', 'user'],
  succession: ['principal'],
  capability: ['principal'],
  user: ['identity-provider', 'mail'],
  'identity-provider': [],
  'directory-sync': ['identity-provider', 'principal'],
  model: [],
  mail: [],
  branding: [],
  connection: ['capability', 'audit'],
  'external-access': ['asset'],
  'search-admin': ['library'],
  audit: [],
  notification: [],
  compliance: [],
  auth: ['user'],
  'link-targets': [],
  settings: [],
  installation: [],
}

/** All files of a theme. */
export function themeGlobs(theme) {
  return [`${featureRoot(theme)}/**`]
}

/** All files of a non-theme bucket (`SHARED`, `APP`, `TEST_INFRA`). */
export function bucketGlobs(bucket) {
  return [`${bucket}/**`]
}
