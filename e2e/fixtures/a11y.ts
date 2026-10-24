import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'

/**
 * axe-core check shared by the accessibility scenarios.
 *
 * Only "serious" and "critical" violations fail the suite - that is the threshold fixed in
 * docs/design/accessibility.md §3.1. "minor"/"moderate" findings are attached to the test as
 * annotations so they show up in the Playwright report without blocking a merge.
 *
 * Exceptions are never global: a scenario that has to skip a rule or a component passes it via
 * `disableRules`/`exclude` and documents the reason and the tracking issue right at the call site.
 *
 * Report mode for the release (docs/releases.md, "Prüfbericht Barrierefreiheit"): with
 * E2E_A11Y_RESULTS_DIR set, every analysis is written there as one JSON record; with
 * E2E_A11Y_REPORT_ONLY=true a serious/critical violation is recorded instead of failing the test,
 * so the scenario goes on to measure its remaining pages.
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

const RESULTS_DIR = process.env.E2E_A11Y_RESULTS_DIR
const REPORT_ONLY = process.env.E2E_A11Y_REPORT_ONLY === 'true'
/**
 * Order of the analyses in this worker process; a new worker after a failed test starts it anew,
 * so the report orders by measuredAt first.
 */
let sequence = 0

/** One analysis as the report of the update package reads it (update_package.py). */
async function writeRecord(
  page: Page,
  context: string,
  options: A11yCheckOptions,
  results: AxeResults,
  unsettled: number,
): Promise<void> {
  if (!RESULTS_DIR) return
  const info = test.info()
  const dark = (await page.evaluate(
    "matchMedia('(prefers-color-scheme: dark)').matches",
  )) as boolean
  sequence += 1
  const record = {
    sequence,
    context,
    test: info.titlePath.slice(1).join(' › '),
    file: info.titlePath[0],
    retry: info.retry,
    path: new URL(page.url()).pathname,
    colorScheme: dark ? 'dark' : 'light',
    measuredAt: results.timestamp,
    axeVersion: results.testEngine.version,
    tags: WCAG_TAGS,
    disabledRules: options.disableRules ?? [],
    excluded: options.exclude ?? [],
    unsettledAnimations: unsettled,
    violations: results.violations.map((violation) => ({
      id: violation.id,
      impact: violation.impact ?? null,
      help: violation.help,
      helpUrl: violation.helpUrl,
      nodes: violation.nodes.length,
      targets: violation.nodes.slice(0, 5).map((node) => node.target.join(' ')),
    })),
    passes: results.passes.map((rule) => rule.id),
    incomplete: results.incomplete.map((rule) => ({ id: rule.id, nodes: rule.nodes.length })),
  }
  mkdirSync(RESULTS_DIR, { recursive: true })
  const name = `${String(sequence).padStart(4, '0')}-${info.testId}-${info.retry}.json`
  writeFileSync(join(RESULTS_DIR, name), JSON.stringify(record, null, 2) + '\n', 'utf8')
}

function describeViolation(violation: Violation): string {
  const targets = violation.nodes
    .slice(0, 5)
    .map((node) => `    - ${node.target.join(' ')}`)
    .join('\n')
  const more = violation.nodes.length > 5 ? `\n    … ${violation.nodes.length - 5} weitere` : ''
  return `  [${violation.impact}] ${violation.id}: ${violation.help}\n    ${violation.helpUrl}\n${targets}${more}`
}

/**
 * Settles the page before it is measured: waits until the running finite animations and
 * transitions have reached their end state. axe derives an element's effective colour from the
 * frame it sees, so a page still fading in yields interpolated colours that exist in no end state,
 * and `color-contrast` reports pairs the interface never shows. Skipped are animations
 * that would never settle - endless repetition (loading indicators), unbounded duration, and
 * paused ones. Returns the number of animations still running when the wait gave up, so a caller
 * can surface that instead of silently measuring an unsettled page. Two frames pass first: a
 * change the caller has just caused (a colour scheme switch) starts its transitions only once the
 * page has rendered it, and an animation that has not started yet cannot be waited for.
 *
 * A string expression: the E2E suite compiles without DOM typings, so `document` is unknown here.
 */
const SETTLE_ANIMATIONS = `
  (async () => {
    await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    const deadline = Date.now() + 5000;
    const pending = () =>
      document.getAnimations().filter((animation) => {
        if (animation.playState === 'finished' || animation.playState === 'paused') return false;
        const timing = animation.effect && animation.effect.getComputedTiming();
        if (!timing) return false;
        return (
          timing.iterations !== Infinity &&
          typeof timing.duration === 'number' &&
          Number.isFinite(timing.duration)
        );
      });
    // Several passes because one animation can start the next (staged entrances); pass count and
    // deadline together keep an unexpected chain from hanging the test instead of reporting it.
    for (let pass = 0; pass < 5; pass++) {
      const running = pending();
      if (running.length === 0) return 0;
      const remaining = deadline - Date.now();
      if (remaining <= 0) break;
      await Promise.race([
        Promise.all(running.map((animation) => animation.finished.catch(() => undefined))),
        new Promise((resolve) => setTimeout(resolve, remaining)),
      ]);
    }
    return pending().length;
  })()
`

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
  const unsettled = (await page.evaluate(SETTLE_ANIMATIONS)) as number
  if (unsettled > 0) {
    test.info().annotations.push({
      type: 'a11y-unsettled',
      description: `${context}: ${unsettled} Animation(en) liefen noch, als axe gemessen hat — gemeldete Farben können Zwischenstände sein (#1643).`,
    })
  }

  let builder = new AxeBuilder({ page }).withTags(WCAG_TAGS)
  if (options.disableRules?.length) {
    builder = builder.disableRules(options.disableRules)
  }
  for (const selector of options.exclude ?? []) {
    builder = builder.exclude(selector)
  }
  const results = await builder.analyze()
  await writeRecord(page, context, options, results, unsettled)

  const failing = results.violations.filter((v) => FAILING_IMPACTS.has(v.impact ?? ''))
  const advisory = results.violations.filter((v) => !FAILING_IMPACTS.has(v.impact ?? ''))

  for (const violation of advisory) {
    test.info().annotations.push({
      type: 'a11y-advisory',
      description: `${context}: [${violation.impact}] ${violation.id} (${violation.nodes.length}×) ${violation.helpUrl}`,
    })
  }

  if (REPORT_ONLY) {
    for (const violation of failing) {
      test.info().annotations.push({
        type: 'a11y-finding',
        description: `${context}: [${violation.impact}] ${violation.id} (${violation.nodes.length}×) ${violation.helpUrl}`,
      })
    }
    return
  }

  expect(
    failing,
    `Barrierefreiheits-Verstöße (serious/critical) auf "${context}":\n${failing
      .map(describeViolation)
      .join('\n')}`,
  ).toEqual([])
}
