import path from 'node:path'
import fs from 'node:fs'
import { sleep } from '../lib/browser.mjs'
import { firstVisible, settle } from '../lib/ui.mjs'

/**
 * Detail page of a knowledge library (/libraries/:id) with every tab. Per (type, scenario) the
 * fixture library of the type is loaded once per tab with `?tab=`; areas are the tabs, `laeufe`
 * (tab „Verlauf“ where a variant has one, else „Quelle“ scrolled to the runs, newest run expanded)
 * and `upload` (upload library: two documents dropped on the upload field, plus an unsupported
 * file in `partial` and `failed`; `slow` is shot while the upload is still under way). A type ending in `-leser` loads the same library with
 * `?mockRole=viewer`; `privat` is the private Nextcloud fixture.
 */

export const description = 'Wissensbibliothek-Detail: Kopf, alle Reiter und Läufe je Quellart'
export const ALL_TYPES = [
  'upload',
  'filesystem',
  'web',
  'rss',
  'confluence',
  'sharepoint',
  'nextcloud',
  'smb',
  's3',
  'gdrive',
  'dropbox',
]
export const EXTRA_TYPES = ['privat', 'upload-leser', 'confluence-leser', 'nextcloud-leser']
export const DEFAULT_SCENARIOS = ['small', 'scale', 'failed', 'empty', 'partial', 'slow']
export const QUICK_SCENARIOS = ['small', 'scale']
export const AREAS = ['dokumente', 'quelle', 'laeufe', 'metadaten', 'freigaben', 'zuordnungen', 'upload']

const LIBRARIES = {
  upload: 'library-upload-rundschreiben',
  filesystem: 'library-filesystem-satzungen',
  web: 'library-web-intranet',
  rss: 'library-rss-presse',
  confluence: 'library-confluence-wiki',
  sharepoint: 'library-sharepoint-rathaus',
  nextcloud: 'library-nextcloud-projekte',
  smb: 'library-smb-ablage',
  s3: 'library-s3-protokolle',
  gdrive: 'library-gdrive-projekte',
  dropbox: 'library-dropbox-projekte',
  privat: 'library-private-ablage',
}

function resolve(type) {
  const reader = type.endsWith('-leser')
  const base = reader ? type.slice(0, -'-leser'.length) : type
  return { id: LIBRARIES[base], upload: base === 'upload', role: reader ? 'viewer' : 'default' }
}

export function jobs({ types = ALL_TYPES, scenarios }) {
  const known = [...ALL_TYPES, ...EXTRA_TYPES]
  const unknown = types.filter((t) => !known.includes(t) || !resolve(t).id)
  if (unknown.length) throw new Error(`unknown type(s) ${unknown.join(',')}; known: ${known.join(',')}`)
  return scenarios.flatMap((scenario) => types.map((type) => ({ type, scenario })))
}

let uploadFiles = []

/** Three small files for the upload field: two documents and one unsupported format. */
export function setup({ out }) {
  const dir = path.join(out, '.upload')
  fs.mkdirSync(dir, { recursive: true })
  uploadFiles = [
    ['Rundschreiben 2026-14.pdf', '%PDF-1.4 Rundschreiben'],
    ['Vermerk Haushaltssperre.docx', 'Vermerk'],
    ['Datenbank Altbestand.mdb', 'binary'],
  ].map(([name, content]) => {
    const file = path.join(dir, name)
    fs.writeFileSync(file, content)
    return file
  })
}

const TABS = { dokumente: null, quelle: 'quelle', laeufe: 'quelle', metadaten: 'metadaten', freigaben: 'freigaben', zuordnungen: 'zuordnungen' }

async function open(page, ctx, job, tab) {
  const { id, role } = resolve(job.type)
  const query = new URLSearchParams({ mockScenario: job.scenario, mockRole: role })
  if (tab) query.set('tab', tab)
  for (let attempt = 0; attempt < 2; attempt++) {
    await page.goto(`${ctx.base}/libraries/${id}?${query}`, { waitUntil: 'domcontentloaded', timeout: 60000 })
    await page.waitForSelector('main, [role=main]', { timeout: 15000 }).catch(() => {})
    await page.getByRole('tab').first().waitFor({ state: 'visible', timeout: 20000 }).catch(() => {})
    // a running run polls for ever; settle on the rendered state instead of network idle
    await settle(page, job.scenario === 'slow' ? 300 : 700)
    await sleep(job.scenario === 'slow' ? 1200 : 200)
    // a request that left before the mock worker took over reaches the dev proxy: load again
    if (!(await page.getByText(/HTTP 50\d: Request failed/).count())) return
    ctx.log(`  ${job.type} ${job.scenario}: proxy answered instead of the mock, reloading`)
  }
}

/** The page at rest: no focus ring, no skip link. */
async function rest(page) {
  await page.evaluate(() => document.activeElement instanceof HTMLElement && document.activeElement.blur())
  const size = page.viewportSize()
  if (size) await page.mouse.move(size.width - 2, size.height - 2)
}

async function tabExists(page, tab) {
  if (!tab) return true
  const label = { quelle: /^Quelle$/, verlauf: /^Verlauf$/, metadaten: /^Metadaten$/, freigaben: /^Freigaben$/, zuordnungen: /^Zuordnungen$/ }[tab]
  return page.getByRole('tab', { name: label }).first().waitFor({ state: 'visible', timeout: 15000 }).then(() => true, () => false)
}

/**
 * A variant with its own tab „Verlauf“: the newest run row (a button in the list „Läufe“) is
 * expanded and the page stays at its top, so the head with the state is in the shot.
 */
async function openHistory(page) {
  const row = page.locator('#library-tabpanel-verlauf ul[aria-label="Läufe"] > li > button').first()
  if (!(await row.count())) return (await page.locator('#library-tabpanel-verlauf').count()) > 0
  if ((await row.getAttribute('aria-expanded')) !== 'true') await row.click().catch(() => {})
  await sleep(400)
  return true
}

/** Expands the newest run, waits for its details and scrolls the „Läufe“ heading to the top. */
async function openRuns(page) {
  const heading = await firstVisible(page.getByRole('heading', { name: /^Läufe$/ }).or(page.getByText(/^Läufe$/)))
  if (!heading) return false
  const first = page.locator('#library-tabpanel-quelle .MuiAccordionSummary-root').first()
  if (await first.count()) {
    if ((await first.getAttribute('aria-expanded')) !== 'true') await first.click().catch(() => {})
    await page.waitForFunction((el) => el?.getAttribute('aria-expanded') === 'true', await first.elementHandle(), { timeout: 5000 }).catch(() => {})
    // the collapse transition ends before the shot
    await page.locator('#library-tabpanel-quelle .MuiCollapse-entered').first().waitFor({ timeout: 5000 }).catch(() => {})
    await sleep(400)
  }
  await heading.evaluate((el) => el.scrollIntoView({ block: 'start' }))
  await sleep(300)
  return true
}

export async function run(page, job, ctx) {
  const { upload } = resolve(job.type)
  for (const area of ctx.areas) {
    if (area === 'upload' && !upload) continue
    if ((area === 'quelle' || area === 'laeufe') && upload) continue
    try {
      if (area === 'upload') {
        await open(page, ctx, job, null)
        const input = page.locator('input[type=file]').first()
        if (!(await input.count())) {
          ctx.notFound(area, 'no file input (reader or no upload field)')
          continue
        }
        // the unsupported file only where a partial failure belongs; `slow` is shot mid-upload
        const files = ['partial', 'failed'].includes(job.scenario) ? uploadFiles : uploadFiles.slice(0, 2)
        await input.setInputFiles(files).catch((e) => ctx.log('  upload', String(e).split('\n')[0]))
        if (job.scenario === 'slow') await sleep(1200)
        else await settle(page, 1200)
        await rest(page)
        await ctx.shoot(area)
        continue
      }
      if (area === 'laeufe') {
        // runs in a tab of their own where the variant has one, else under „Quelle“
        await open(page, ctx, job, 'verlauf')
        if (await page.getByRole('tab', { name: /^Verlauf$/ }).count()) {
          if (!(await openHistory(page))) {
            ctx.notFound(area, 'no run list')
            continue
          }
          await rest(page)
          await ctx.shoot(area)
          continue
        }
      }
      const tab = TABS[area]
      await open(page, ctx, job, tab)
      if (!(await tabExists(page, tab))) {
        ctx.notFound(area, `tab ${tab} not offered`)
        continue
      }
      if (area === 'laeufe' && !(await openRuns(page))) {
        ctx.notFound(area, 'no runs section (reader)')
        continue
      }
      await rest(page)
      await ctx.shoot(area)
    } catch (e) {
      ctx.error(area, e)
    }
  }
}
