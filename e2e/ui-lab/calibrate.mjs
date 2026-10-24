#!/usr/bin/env node
/*
 * Calibration of the capture measurements against two static pages with known results.
 *
 *   node ui-lab/calibrate.mjs [--chromium <path>]
 *
 * calibration/good.html passes contrast, axe and the count budgets in both schemes;
 * calibration/bad.html fails contrast in both schemes, misses a label and a button name, has a
 * too small target and exceeds every count budget. Both are measured exactly as capture.mjs
 * measures (scheme set before the first load). A counter-check measures good.html the discarded
 * way, switching to dark within the session, and reports the contrast findings that produces.
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
  { page: 'good.html', scheme: 'light', check: (r) => r.axe.violations.length === 0 && r.budgets.length === 0, expect: 'keine axe-Verstöße, keine Budgetüberschreitung' },
  { page: 'good.html', scheme: 'dark', check: (r) => r.axe.contrastViolations === 0 && r.shown === 'dark', expect: 'kein Kontrastverstoß, dunkel dargestellt' },
  {
    page: 'bad.html',
    scheme: 'light',
    check: (r) =>
      r.axe.contrastViolations > 0 &&
      ['label', 'button-name', 'target-size'].every((id) => ids(r.axe).includes(id)) &&
      ['helpTextsPerForm', 'helpTextLines', 'uiWords'].every((b) => r.budgets.includes(b)),
    expect: 'Kontrast, label, button-name, target-size; alle drei Budgets überschritten',
  },
  { page: 'bad.html', scheme: 'dark', check: (r) => r.axe.contrastViolations > 0 && r.shown === 'dark', expect: 'Kontrastverstoß, dunkel dargestellt' },
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

/** The discarded way: load light, switch the media query to dark, measure after the old pause. */
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
  console.log(`${ok ? 'OK  ' : 'FAIL'} ${e.page} ${e.scheme}: erwartet ${e.expect}`)
  console.log(
    `     axe ${JSON.stringify(ids(r.axe))} · Kontrast ${r.axe.contrastViolations}` +
      (r.axe.contrastSamples.length ? ` (z. B. ${r.axe.contrastSamples[0].fg} auf ${r.axe.contrastSamples[0].bg} = ${r.axe.contrastSamples[0].ratio}:1)` : '') +
      ` · Budgets ${JSON.stringify(r.budgets)} · Hilfetexte ${r.metrics.helpTextsPerForm}, max. ${r.metrics.helpTextMaxLines} Zeilen · Wörter ${r.metrics.uiWords} · Schema ${r.shown}`,
  )
}
const switched = await measureSwitched(browser)
console.log(
  `INFO good.html in der Sitzung auf dunkel umgeschaltet: ${switched.contrastViolations} Kontrastbefunde` +
    (switched.contrastSamples?.length ? ` (z. B. ${switched.contrastSamples[0].fg} auf ${switched.contrastSamples[0].bg} = ${switched.contrastSamples[0].ratio}:1)` : '') +
    ' – deshalb lädt capture.mjs jedes Schema direkt',
)
await browser.close()
process.exit(failed ? 1 : 0)
