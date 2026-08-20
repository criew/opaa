import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

/**
 * axe-core check shared by the accessibility scenarios (#586).
 *
 * Only "serious" and "critical" violations fail the suite - that is the threshold fixed in
 * docs/design/accessibility.md §3.1. "minor"/"moderate" findings are attached to the test as
 * annotations so they show up in the Playwright report without blocking a merge.
 *
 * Exceptions are never global: a scenario that has to skip a rule or a component passes it via
 * `disableRules`/`exclude` and documents the reason and the tracking issue right at the call site.
 */

/** WCAG 2.1 AA is the target level (BITV 2.0); best-practice rules are deliberately left out. */
const WCAG_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']
const FAILING_IMPACTS = new Set(['serious', 'critical'])

interface A11yCheckOptions {
  /** axe rule IDs to skip for this page - document reason + issue at the call site. */
  disableRules?: string[]
  /**
   * CSS selectors excluded from the analysis altogether - narrower than disabling a rule, for a
   * known violation confined to one component. Document reason + issue at the call site.
   */
  exclude?: string[]
}

type AxeResults = Awaited<ReturnType<AxeBuilder['analyze']>>
type Violation = AxeResults['violations'][number]

function describeViolation(violation: Violation): string {
  const targets = violation.nodes
    .slice(0, 5)
    .map((node) => `    - ${node.target.join(' ')}`)
    .join('\n')
  const more = violation.nodes.length > 5 ? `\n    … ${violation.nodes.length - 5} weitere` : ''
  return `  [${violation.impact}] ${violation.id}: ${violation.help}\n    ${violation.helpUrl}\n${targets}${more}`
}

/**
 * Runs axe against the current page state and fails on serious/critical violations.
 *
 * @param context human-readable label for the page/state under test, used in the failure message
 */
export async function expectNoSeriousA11yViolations(
  page: Page,
  context: string,
  options: A11yCheckOptions = {},
): Promise<void> {
  let builder = new AxeBuilder({ page }).withTags(WCAG_TAGS)
  if (options.disableRules?.length) {
    builder = builder.disableRules(options.disableRules)
  }
  for (const selector of options.exclude ?? []) {
    builder = builder.exclude(selector)
  }
  const results = await builder.analyze()

  const failing = results.violations.filter((v) => FAILING_IMPACTS.has(v.impact ?? ''))
  const advisory = results.violations.filter((v) => !FAILING_IMPACTS.has(v.impact ?? ''))

  for (const violation of advisory) {
    test.info().annotations.push({
      type: 'a11y-advisory',
      description: `${context}: [${violation.impact}] ${violation.id} (${violation.nodes.length}×) ${violation.helpUrl}`,
    })
  }

  expect(
    failing,
    `Barrierefreiheits-Verstöße (serious/critical) auf "${context}":\n${failing
      .map(describeViolation)
      .join('\n')}`,
  ).toEqual([])
}
