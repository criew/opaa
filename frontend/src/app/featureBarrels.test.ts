import { describe, expect, it } from 'vitest'

/**
 * The build treats every theme's index.ts as free of side effects (vite.config.ts) and loads
 * pages only through routes.ts (lazyPages.ts). Both rely on the files holding nothing but
 * re-exports, and on pages never leaving through index.ts.
 */
const raw = (files: Record<string, unknown>) => files as Record<string, string>
const INDEX_FILES = raw(
  import.meta.glob('../features/*/index.ts', { query: '?raw', import: 'default', eager: true }),
)
const ROUTES_FILES = raw(
  import.meta.glob('../features/*/routes.ts', { query: '?raw', import: 'default', eager: true }),
)

const RE_EXPORT = /export\s+(?:type\s+)?(?:\*(?:\s+as\s+\w+)?|\{[^}]*\})\s+from\s+'([^']+)'/g

function parse(source: string) {
  const code = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
  const targets = [...code.matchAll(RE_EXPORT)].map((match) => match[1])
  return { targets, rest: code.replace(RE_EXPORT, '').trim() }
}

describe('theme barrels', () => {
  it('finds the files it checks', () => {
    expect(Object.keys(INDEX_FILES).length).toBeGreaterThan(10)
    expect(Object.keys(ROUTES_FILES).length).toBeGreaterThan(10)
  })

  it.each(Object.entries(INDEX_FILES))('%s only re-exports, and no page', (_, source) => {
    const { targets, rest } = parse(source)
    expect(rest).toBe('')
    expect(targets.filter((target) => target.startsWith('./pages/'))).toEqual([])
  })

  it.each(Object.entries(ROUTES_FILES))('%s only re-exports its own pages', (_, source) => {
    const { targets, rest } = parse(source)
    expect(rest).toBe('')
    expect(targets.length).toBeGreaterThan(0)
    expect(targets.filter((target) => !target.startsWith('./pages/'))).toEqual([])
  })
})
