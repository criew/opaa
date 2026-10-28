import { describe, expect, it } from 'vitest'

/**
 * The application's source text, read in by Vite (`?raw`) — no file system access, so that the
 * test gets by without Node types.
 */
const SOURCES = import.meta.glob('../../**/*.tsx', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

const PRODUCTION = Object.entries(SOURCES).filter(
  ([path]) => !path.includes('.test.') && !path.includes('/mocks/'),
)

const count = (text: string, pattern: RegExp) => text.match(pattern)?.length ?? 0

/**
 * The trigger of further actions looks and behaves the same everywhere (guidelines 5.6): the
 * horizontal „⋯", and on pointing the tooltip „Weitere Aktionen" beside its own aria-label.
 */
describe('„⋯" trigger of further actions', () => {
  it('uses the horizontal three dots, never the vertical ones', () => {
    const vertical = PRODUCTION.filter(([, text]) => /MoreVert/.test(text)).map(([path]) => path)
    expect(vertical).toEqual([])
  })

  it('renders every trigger through RowMenu, never a rebuilt menu', () => {
    const rebuilt = PRODUCTION.filter(
      ([path, text]) => /MoreHoriz/.test(text) && !path.endsWith('/RowMenu.tsx'),
    ).map(([path]) => path)
    expect(rebuilt).toEqual([])
  })

  it('carries the tooltip „Weitere Aktionen" at every trigger', () => {
    const withoutTooltip = PRODUCTION.filter(
      ([, text]) => count(text, /<MoreHorizIcon\b/g) > count(text, /title="Weitere Aktionen"/g),
    ).map(([path]) => path)
    expect(withoutTooltip).toEqual([])
  })
})
