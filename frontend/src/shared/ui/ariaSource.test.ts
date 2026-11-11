import { describe, expect, it } from 'vitest'

/*
 * Source guards for roles and heading levels that axe reports (accessibility.md, guidelines 4.4).
 * Read through Vite's `?raw` import, so the guard gets by without Node types.
 */
const SOURCES = import.meta.glob('../../**/*.tsx', {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>

const PRODUCTION = Object.entries(SOURCES).filter(
  ([path]) => !path.includes('.test.') && !path.includes('/mocks/'),
)

/*
 * axe `heading-order`: MUI wraps every AccordionSummary in an h3 unless told otherwise, which skips
 * a level right below a page title. Every accordion names its level through `slotProps.heading`,
 * and no summary carries a heading of its own inside that wrapper.
 */
const ACCORDIONS = PRODUCTION.filter(([, source]) => /<Accordion[\s>]/.test(source))

/** Each `<Accordion …>` opening tag, read up to its summary (arrow functions contain `>`). */
function accordionTags(source: string): string[] {
  return [...source.matchAll(/<Accordion(?=[\s>])[\s\S]*?(?=<AccordionSummary)/g)].map(
    (match) => match[0],
  )
}

describe('accordion headings in the source', () => {
  it('finds the accordions of the application', () => {
    expect(ACCORDIONS.length).toBeGreaterThan(4)
  })

  it.each(ACCORDIONS)('%s names the heading level of each accordion', (_path, source) => {
    for (const tag of accordionTags(source)) {
      expect(tag).toMatch(/slotProps=\{\{\s*heading:\s*\{\s*component:\s*'h[2-6]'/)
    }
  })

  it.each(ACCORDIONS)('%s puts no heading inside an accordion summary', (_path, source) => {
    for (const summary of source.matchAll(/<AccordionSummary[\s\S]*?<\/AccordionSummary>/g)) {
      expect(summary[0]).not.toMatch(/component="h[1-6]"|<h[1-6][\s>]/)
    }
  })
})

/*
 * axe `aria-allowed-role`: a `<Button component="label">` is a label with the role of a button,
 * which ARIA does not allow. A file is chosen through a real button that clicks a hidden input
 * (pattern of `TemplateFilePicker`).
 */
describe('buttons in the source', () => {
  it.each(PRODUCTION)('%s renders no button as a label', (_path, source) => {
    // up to the first closing tag or self-closing child: the props stand before either
    for (const button of source.matchAll(/<(Button|IconButton)(?=[\s>])[\s\S]*?(?:<\/\1>|\/>)/g)) {
      expect(button[0]).not.toMatch(/component="label"/)
    }
  })
})
