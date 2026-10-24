#!/usr/bin/env node
/*
 * UI-Lab capture: screenshots and metrics of the frontend in MSW mock mode.
 *
 *   node ui-lab/capture.mjs --base http://localhost:5191 --out <dir>
 *        [--flow libraries|pages] [--pages /spaces,/admin/users]
 *        [--types filesystem,confluence,...] [--scenarios small,scale,...]
 *        [--schemes light,dark] [--widths 1280,320] [--areas wizard,review,detail,edit]
 *        [--quick] [--no-axe] [--no-full] [--concurrency 2] [--chromium <path>]
 *
 * Each scheme gets its own browser context that loads every page in that scheme from the start;
 * in the "slow" scenario listings and connection tests never answer, so its shots show one state.
 * Per job (type or page × scenario) the flow runs in a fresh page load, so mock state and scenario
 * start anew; a state that cannot be reached is recorded as {"status":"not-found"} instead of
 * failing the run. Output: <name>.png, <name>__full.png, metrics.jsonl and run.json in --out.
 */
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { launch, schemeContext } from './lib/browser.mjs'
import { createRecorder } from './lib/measure.mjs'

const HERE = path.dirname(fileURLToPath(import.meta.url))
const FLOWS = ['libraries', 'pages']

function parseArgs(argv) {
  const args = { base: 'http://localhost:5191', out: path.join(HERE, 'out'), flow: 'libraries' }
  const list = (v) => v.split(',').map((s) => s.trim()).filter(Boolean)
  const count = (a, v) => {
    const n = Number(v)
    if (!Number.isInteger(n) || n <= 0) throw new Error(`${a} needs a positive whole number, got ${v}`)
    return n
  }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]
    const next = () => {
      const v = argv[++i]
      if (v === undefined) throw new Error(`${a} needs a value`)
      return v
    }
    if (a === '--base') args.base = next().replace(/\/$/, '')
    else if (a === '--out') args.out = path.resolve(next())
    else if (a === '--flow') args.flow = next()
    else if (a === '--pages') args.pages = list(next())
    else if (a === '--types') args.types = list(next())
    else if (a === '--scenarios') args.scenarios = list(next())
    else if (a === '--schemes') args.schemes = list(next())
    else if (a === '--widths') args.widths = list(next()).map((w) => count(a, w))
    else if (a === '--areas') args.areas = list(next())
    else if (a === '--quick') args.quick = true
    else if (a === '--no-axe') args.noAxe = true
    else if (a === '--no-full') args.noFull = true
    else if (a === '--concurrency') args.concurrency = count(a, next())
    else if (a === '--chromium') args.chromium = next()
    else if (a === '--help' || a === '-h') {
      console.log(fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('*/')[0])
      process.exit(0)
    } else throw new Error(`unknown argument ${a}`)
  }
  if (!FLOWS.includes(args.flow)) throw new Error(`unknown flow ${args.flow}; known: ${FLOWS.join(', ')}`)
  return args
}

const ARGS = parseArgs(process.argv.slice(2))
const flow = await import(`./flows/${ARGS.flow}.mjs`)
if (ARGS.quick) {
  ARGS.schemes ??= ['light']
  ARGS.widths ??= [1280]
  ARGS.scenarios ??= flow.QUICK_SCENARIOS
}
ARGS.scenarios ??= flow.DEFAULT_SCENARIOS
ARGS.schemes ??= ['light', 'dark']
ARGS.widths ??= [1280, 320]
ARGS.areas ??= flow.AREAS
ARGS.concurrency ??= 2
flow.jobs(ARGS) // fails on missing or unknown flow arguments before a browser starts

const T0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - T0) / 1000).toFixed(0).padStart(5)}s]`, ...a)
const recorder = createRecorder({ out: ARGS.out, widths: ARGS.widths, noAxe: ARGS.noAxe, noFull: ARGS.noFull, log })
flow.setup?.({ out: ARGS.out })

log(`flow ${ARGS.flow} | base ${ARGS.base} -> ${ARGS.out}`)
log(`scenarios ${ARGS.scenarios.join(',')} | schemes ${ARGS.schemes.join(',')} | widths ${ARGS.widths.join(',')}`)
const browser = await launch(ARGS.chromium)
for (const scheme of ARGS.schemes) {
  const context = await schemeContext(browser, scheme, { width: ARGS.widths[0] })
  // the "slow" scenario stays in its loading state, so every scheme and width shows the same state
  await context.addInitScript(() => {
    try {
      sessionStorage.setItem('opaa.mockSlowMs', String(10 * 60 * 1000))
    } catch {
      /* storage blocked: the scenario falls back to its default latency */
    }
  })
  const queue = flow.jobs(ARGS)
  const worker = async () => {
    const page = await context.newPage()
    page.on('pageerror', (e) => log('  PAGEERROR', e.message.slice(0, 160)))
    for (let job = queue.shift(); job; job = queue.shift()) {
      const key = { type: job.type, scenario: job.scenario, scheme }
      log(`== ${job.type} / ${job.scenario} / ${scheme}`)
      const ctx = {
        base: ARGS.base,
        areas: ARGS.areas,
        log,
        shoot: (area, extra) => recorder.shoot(page, { ...key, area }, extra),
        notFound: (area, reason) => recorder.notFound({ ...key, area }, reason),
        error: (area, e) => {
          const reason = String(e?.message ?? e).split('\n')[0]
          log(`  ${area} flow error`, reason)
          recorder.record({ ...key, area, status: 'error', reason })
        },
      }
      try {
        await flow.run(page, job, ctx)
      } catch (e) {
        ctx.error('flow', e)
      }
    }
    await page.close()
  }
  await Promise.all(Array.from({ length: Math.max(1, ARGS.concurrency) }, worker))
  await context.close()
}
await browser.close()
const seconds = Number(((Date.now() - T0) / 1000).toFixed(0))
log(`done: ${recorder.shotCount()} shots in ${seconds}s`)
fs.writeFileSync(
  path.join(ARGS.out, 'run.json'),
  JSON.stringify({ args: ARGS, shots: recorder.shotCount(), seconds, finishedAt: new Date().toISOString() }, null, 2),
)
