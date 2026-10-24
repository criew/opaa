#!/usr/bin/env node
/*
 * Calibration of the capture measurements against two static pages with known results.
 *
 *   node ui-lab/calibrate.mjs [--chromium <path>]
 *
 * calibration/good.html passes axe and the count budgets in light and contrast and the budgets in dark;
 * calibration/bad.html fails contrast in both schemes, misses a label and a button name, has a
 * too small target and exceeds every count budget. Both are measured exactly as capture.mjs
 * measures (scheme set before the first load). A counter-check switches good.html to dark within
 * its session and reports the contrast findings that produces while the theme transitions run.
 * Exit code 1 when a measurement deviates from the expectation.
 */
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { appliedScheme, launch, schemeContext, sleep } from './lib/browser.mjs'
import { budgetViolations, pageMetrics, runAxe } from './lib/measure.mjs'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const url = (name) => pathToFileURL(path.join(HERE, 'calibration', name)).href
const argv = process.argv.slice(2)
const chromiumAt = argv.indexOf('--chromium')
const chromiumPath = chromiumAt >= 0 ? argv[chromiumAt + 1] : undefined

const ids = (axe) => (axe?.violations ?? []).map((v) => v.id)

const EXPECTATIONS = [
  { page: 'good.html', scheme: 'light', check: (r) => r.axe.violations.length === 0 && r.budgets.length === 0, expect: 'no axe violation, no budget exceeded' },
  { page: 'good.html', scheme: 'dark', check: (r) => r.axe.contrastViolations === 0 && r.budgets.length === 0 && r.shown === 'dark', expect: 'no contrast violation, no budget exceeded, shown dark' },
  {
    page: 'bad.html',
    scheme: 'light',
    check: (r) =>
      r.axe.contrastViolations > 0 &&
      ['label', 'button-name', 'target-size'].every((id) => ids(r.axe).includes(id)) &&
      ['helpTextsPerForm', 'helpTextLines', 'uiWords'].every((b) => r.budgets.includes(b)),
    expect: 'contrast, label, button-name, target-size; all three budgets exceeded',
  },
  { page: 'bad.html', scheme: 'dark', check: (r) => r.axe.contrastViolations > 0 && r.shown === 'dark', expect: 'contrast violation, shown dark' },
]

async function measure(browser, page, scheme) {
  const context = await schemeContext(browser, scheme)
  const tab = await context.newPage()
  await tab.goto(url(page))
  await sleep(300)
  const metrics = await pageMetrics(tab)
  const axe = await runAxe(tab, { contrastOnly: scheme !== 'light' })
  const shown = await appliedScheme(tab)
  await context.close()
  return { metrics, axe, shown, budgets: budgetViolations(metrics, 1280, 'small') }
}

/** Loads light, switches the media query to dark and measures 400 ms later, mid-transition. */
async function measureSwitched(browser) {
  const context = await schemeContext(browser, 'light')
  const tab = await context.newPage()
  await tab.goto(url('good.html'))
  await sleep(300)
  await tab.emulateMedia({ colorScheme: 'dark' })
  await sleep(400)
  const axe = await runAxe(tab, { contrastOnly: true })
  await context.close()
  return axe
}

const browser = await launch(chromiumPath)
let failed = 0
for (const e of EXPECTATIONS) {
  const r = await measure(browser, e.page, e.scheme)
  if (r.axe.error) throw new Error(`axe failed on ${e.page}: ${r.axe.error}`)
  const ok = e.check(r)
  if (!ok) failed++
  console.log(`${ok ? 'OK  ' : 'FAIL'} ${e.page} ${e.scheme}: expected ${e.expect}`)
  console.log(
    `     axe ${JSON.stringify(ids(r.axe))} · contrast ${r.axe.contrastViolations}` +
      (r.axe.contrastSamples.length ? ` (e.g. ${r.axe.contrastSamples[0].fg} on ${r.axe.contrastSamples[0].bg} = ${r.axe.contrastSamples[0].ratio}:1)` : '') +
      ` · budgets ${JSON.stringify(r.budgets)} · help texts ${r.metrics.helpTextsPerForm}, max ${r.metrics.helpTextMaxLines} lines · words ${r.metrics.uiWords} · scheme ${r.shown}`,
  )
}
const switched = await measureSwitched(browser)
console.log(
  `INFO good.html switched to dark within the session: ${switched.contrastViolations} contrast findings` +
    (switched.contrastSamples?.length ? ` (e.g. ${switched.contrastSamples[0].fg} on ${switched.contrastSamples[0].bg} = ${switched.contrastSamples[0].ratio}:1)` : '') +
    ', which is why capture.mjs loads every scheme directly',
)
await browser.close()
process.exit(failed ? 1 : 0)
