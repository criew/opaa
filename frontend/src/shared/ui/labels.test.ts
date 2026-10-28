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

/**
 * Every `<Chip …>` opening tag with all its props, across line breaks. The tag ends at the first
 * `>` outside braces, so a nested element (`icon={<StarIcon />}`) or an arrow function in a prop
 * never cuts the props short.
 */
function chipTags(text: string): string[] {
  const tags: string[] = []
  for (const match of text.matchAll(/<Chip(?![\w.])/g)) {
    let depth = 0
    for (let i = match.index; i < text.length; i++) {
      const char = text[i]
      if (char === '{') depth++
      else if (char === '}') depth--
      else if (char === '>' && depth === 0) {
        tags.push(text.slice(match.index, i + 1))
        break
      }
    }
  }
  return tags
}

const SIGNAL = /color=(?:"(?:warning|error|success)"|\{[^}]*'(?:warning|error|success)')/
const COMPUTED = /color=\{[\w.]+\}/
const coloured = (tag: string) => SIGNAL.test(tag) || COMPUTED.test(tag)

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
    expect(PRODUCTION.flatMap(([, text]) => chipTags(text)).length).toBeGreaterThan(10)
  })

  it('reads every prop of a chip, also behind an icon or an arrow function', () => {
    const source = `<Chip
      icon={<StarIcon />}
      onDelete={() => remove(id)}
      label="Nicht verfügbar"
      color="warning"
    />`
    expect(chipTags(source)).toHaveLength(1)
    expect(coloured(chipTags(source)[0])).toBe(true)
    expect(coloured(chipTags('<Chip icon={<X />} color={state.color} />')[0])).toBe(true)
    expect(coloured(chipTags('<Chip icon={<X />} variant="outlined" />')[0])).toBe(false)
  })

  it('never colours a chip in a signal colour', () => {
    expect(offending(coloured)).toEqual([])
  })

  it('shows „Standard" and „Archiviert" as the shared MetaBadge, never as a chip', () => {
    const word = /label=(?:"|\{[^}]*')(?:Standard|Archiviert)["']/
    expect(offending((tag) => word.test(tag))).toEqual([])
  })

  it('shows „lange offen" as a state with its dot, never as a label', () => {
    const asLabel = /<MetaBadge[^>]*>\s*lange offen\s*<\/MetaBadge>|<Chip[^>]*label="lange offen"/i
    expect(PRODUCTION.filter(([, text]) => asLabel.test(text)).map(([path]) => path)).toEqual([])
    expect(
      PRODUCTION.some(([, text]) => /<StateDot\s+tone="warning"\s+label="lange offen"/.test(text)),
    ).toBe(true)
  })
})
