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

/** Every `<Chip … />` with its props, across line breaks, up to the first self-closing end. */
function chipTags(text: string): string[] {
  return text.match(/<Chip\b[\s\S]*?\/>/g) ?? []
}

function offending(predicate: (tag: string) => boolean): string[] {
  return PRODUCTION.flatMap(([path, text]) =>
    chipTags(text)
      .filter(predicate)
      .map((tag) => `${path}: ${tag.replace(/\s+/g, ' ')}`),
  )
}

/**
 * Guidelines 5.5: a label is quiet - outline or ink surface, never a signal colour. A state is a
 * dot before its word (`StateDot`), not a coloured chip.
 */
describe('labels', () => {
  it('finds the chips it checks', () => {
    expect(PRODUCTION.flatMap(([, text]) => chipTags(text)).length).toBeGreaterThan(20)
  })

  it('never colours a chip in a signal colour', () => {
    const signal = /color=(?:"(?:warning|error|success)"|\{[^}]*'(?:warning|error|success)')/
    const computed = /color=\{[\w.]+\}/
    expect(offending((tag) => signal.test(tag) || computed.test(tag))).toEqual([])
  })

  it('shows „Standard" and „Archiviert" as the shared MetaBadge, never as a chip', () => {
    expect(offending((tag) => /label="(?:Standard|Archiviert)"/.test(tag))).toEqual([])
  })
})
