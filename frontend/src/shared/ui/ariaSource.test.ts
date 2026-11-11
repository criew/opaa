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

/** A button of MUI or of shared/ui, up to its first closing tag or self-closing child. */
const BUTTON =
  /<(Button|IconButton|Fab|ToggleButton|LoadingButton|BusyButton)(?=[\s>])[\s\S]*?(?:<\/\1>|\/>)/g
const LABEL_COMPONENT = /component=\{?['"]label['"]/
const HEADING_SLOT = /slotProps=\{\{[\s\S]*\bheading:\s*\{[^}]*\bcomponent:\s*['"]h[2-6]['"]/

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
      // any order of the slots and of the keys inside the heading slot
      expect(tag).toMatch(HEADING_SLOT)
    }
  })

  it.each(ACCORDIONS)('%s puts no heading inside an accordion summary', (_path, source) => {
    for (const summary of source.matchAll(/<AccordionSummary[\s\S]*?<\/AccordionSummary>/g)) {
      expect(summary[0]).not.toMatch(/component=\{?['"]h[1-6]['"]|<h[1-6][\s>]/)
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
    for (const button of source.matchAll(BUTTON)) {
      expect(button[0]).not.toMatch(LABEL_COMPONENT)
    }
  })
})

describe('the patterns of the guards', () => {
  it.each([
    '<Button component="label">Datei</Button>',
    '<Fab component={\'label\'} color="primary" />',
    '<ToggleButton value="a" component=\'label\'>A</ToggleButton>',
    '<LoadingButton\n  loading={busy}\n  component={"label"}\n>\n  Hochladen\n</LoadingButton>',
  ])('finds the label in %s', (source) => {
    const [button] = [...source.matchAll(BUTTON)]
    expect(button?.[0]).toMatch(LABEL_COMPONENT)
  })

  it.each([
    "slotProps={{ heading: { component: 'h2' } }}",
    'slotProps={{ transition: { unmountOnExit: true }, heading: { id: x, component: "h3" } }}',
  ])('reads the heading level in %s', (tag) => {
    expect(tag).toMatch(HEADING_SLOT)
  })
})
