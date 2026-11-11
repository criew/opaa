#!/usr/bin/env node
/**
 * Page pass on the phone (guidelines 4.7, UI checklist P25): loads every administration page and
 * the main pages with the mock profiles admin, user and auditor at 360 px and measures
 *   - contentTop: top edge of the page's <h1>, at most 200 px below the top of the viewport,
 *   - overflowX: no sideways scrolling of the page,
 *   - tables: no visible <table> (below 900 px every list is a card list),
 *   - wrappedLabels: no button or tab whose label breaks onto a second line,
 *   - cutOff: no text cut off at the left or right edge of the viewport,
 *   - createButton: the create button of the page header spans the content width,
 *   - dialog: the dialog it opens fills the screen with its footer at the bottom,
 * and again at 320 px (WCAG reflow) only `overflowX` and `cutOff`. Tabs of a page (links with
 * role "tab") are visited as pages of their own. Exits with 1 when a page breaks a rule.
 *
 *   node ui-lab/mobile-pass.mjs --base http://localhost:5191 [--out <dir>] [--profiles admin,user]
 *        [--only /admin/users] [--shots] [--chromium <path>]
 */
import { mkdir, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { launch, pinProfile, schemeContext } from './lib/browser.mjs'
import { settle } from './lib/ui.mjs'

export const CONTENT_TOP_MAX = 200
export const PHONE_WIDTH = 360
export const REFLOW_WIDTH = 320

/** Start pages per profile; tabs are found on the page. */
export const PAGES = {
  admin: [
    '/chat',
    '/spaces',
    '/catalog',
    '/libraries/library-nextcloud-projekte',
    '/settings',
    '/admin/branding',
    '/admin/settings-transfer',
    '/admin/users',
    '/admin/groups',
    '/admin/capabilities',
    '/admin/succession',
    '/admin/models',
    '/admin/identity-providers',
    '/admin/directory-sync',
    '/admin/mail',
    '/admin/malware-scan',
    '/admin/agents',
    '/agent-directory',
    '/admin/templates',
    '/admin/connections',
    '/admin/search',
    '/admin/privacy',
  ],
  user: ['/chat', '/spaces', '/catalog', '/settings'],
  auditor: ['/revision/rechtehistorie', '/revision/verbindungsprotokoll', '/revision/rollenantraege'],
}

/** The installation assistant hangs on no profile; its step list is checked like a page. */
const EXTRA = [{ profile: 'admin', path: '/installation?mockInstallation=pending', label: 'installation' }]

function args(argv) {
  const out = { base: null, out: null, profiles: Object.keys(PAGES), only: null, shots: false, chromium: undefined }
  for (let i = 0; i < argv.length; i++) {
    const key = argv[i]
    const next = () => argv[++i]
    if (key === '--base') out.base = next()
    else if (key === '--out') out.out = next()
    else if (key === '--profiles') out.profiles = next().split(',')
    else if (key === '--only') out.only = next().split(',')
    else if (key === '--shots') out.shots = true
    else if (key === '--chromium') out.chromium = next()
    else throw new Error(`unknown argument ${key}`)
  }
  if (!out.base) throw new Error('--base http://localhost:<port> is required')
  return out
}

/** Runs in the page: every measure of one viewport. */
function measureInPage() {
  const vw = document.documentElement.clientWidth
  const visible = (el) => {
    const style = getComputedStyle(el)
    if (style.visibility === 'hidden' || style.display === 'none') return false
    const rect = el.getBoundingClientRect()
    return rect.width > 0 && rect.height > 0
  }
  // An ancestor that scrolls or clips sideways shows its overflow on purpose (navigation row).
  const clippedByAncestor = (el) => {
    for (let node = el.parentElement; node && node !== document.body; node = node.parentElement) {
      const overflow = getComputedStyle(node).overflowX
      if (overflow !== 'visible') return true
    }
    return false
  }
  // Lines of the visible label; visually hidden text (a 1 px clipped span) does not count.
  const lineCount = (el) => {
    const tops = []
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT)
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const parent = node.parentElement
      if (!node.textContent.trim() || !parent || !visible(parent)) continue
      const box = parent.getBoundingClientRect()
      if (box.width <= 2 || box.height <= 2) continue
      const range = document.createRange()
      range.selectNodeContents(node)
      for (const rect of range.getClientRects()) {
        if (rect.width < 1 || rect.height < 1) continue
        if (!tops.some((top) => Math.abs(top - rect.top) < rect.height / 2)) tops.push(rect.top)
      }
    }
    return tops.length
  }
  const name = (el) => (el.innerText || el.getAttribute('aria-label') || '').trim().replace(/\s+/g, ' ').slice(0, 60)

  const h1 = [...document.querySelectorAll('main h1, [role=main] h1, h1')].find(visible)
  const contentTop = h1 ? Math.round(h1.getBoundingClientRect().top + window.scrollY) : null
  const overflowX = Math.max(0, document.documentElement.scrollWidth - vw)
  const tables = [...document.querySelectorAll('table')].filter(visible).map((t) => t.getAttribute('aria-label') || 'table')
  // Labels of buttons, tabs, toggles and the area navigation; links in text and cards may wrap.
  const wrappedLabels = [
    ...document.querySelectorAll('.MuiButton-root, .MuiTab-root, .MuiToggleButton-root, .MuiChip-root, nav li > a'),
  ]
    .filter((el) => visible(el) && name(el) && !el.closest('[role=menu], [role=listbox], [role=tooltip]'))
    .filter((el) => lineCount(el) > 1)
    .map(name)
  const cutOff = []
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT)
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    if (!node.textContent.trim()) continue
    const el = node.parentElement
    if (!el || !visible(el) || clippedByAncestor(el)) continue
    const range = document.createRange()
    range.selectNodeContents(node)
    const rect = range.getBoundingClientRect()
    if (rect.width < 1) continue
    if (rect.left < -1 || rect.right > vw + 1) cutOff.push(node.textContent.trim().slice(0, 40))
  }
  const create = [...document.querySelectorAll('[data-area-page-header] [data-create-button]')].find(visible)
  const title = document.querySelector('[data-area-page-header] h1')
  const createButton = create
    ? {
        label: name(create),
        fullWidth:
          create.getBoundingClientRect().width >= create.closest('[data-area-page-header]').getBoundingClientRect().width - 1,
        belowTitle: create.getBoundingClientRect().top >= title.getBoundingClientRect().bottom - 1,
      }
    : null
  const tabs = [...document.querySelectorAll('a[role=tab][href]')].map((a) => a.getAttribute('href'))
  const stepList = document.querySelector('[aria-label*="Schritt"], nav ol')
  return {
    contentTop,
    heading: h1 ? h1.innerText.trim() : null,
    overflowX,
    tables,
    wrappedLabels: [...new Set(wrappedLabels)],
    cutOff: [...new Set(cutOff)].slice(0, 8),
    tabs,
    createButton,
    stepListHeight: stepList && visible(stepList) ? Math.round(stepList.getBoundingClientRect().height) : null,
  }
}

function violations(m, width) {
  const found = []
  if (m.loadError) return [`loadError ${m.loadError}`]
  if (m.overflowX > 0) found.push(`overflowX ${m.overflowX}px`)
  if (m.cutOff.length) found.push(`cutOff ${JSON.stringify(m.cutOff)}`)
  if (width === PHONE_WIDTH) {
    if (m.contentTop === null) found.push('no <h1>')
    else if (m.contentTop > CONTENT_TOP_MAX) found.push(`contentTop ${m.contentTop}px`)
    if (m.tables.length) found.push(`tables ${JSON.stringify(m.tables)}`)
    if (m.wrappedLabels.length) found.push(`wrappedLabels ${JSON.stringify(m.wrappedLabels)}`)
    if (m.createButton && !(m.createButton.fullWidth && m.createButton.belowTitle)) {
      found.push(`createButton ${JSON.stringify(m.createButton)}`)
    }
    if (m.dialog && !(m.dialog.fillsScreen && m.dialog.footerAtBottom)) {
      found.push(`dialog ${JSON.stringify(m.dialog)}`)
    }
  }
  return found
}

/** Opens the dialog of the header's create button and measures it; `null` without a dialog. */
async function openDialog(page) {
  const button = page.locator('[data-area-page-header] [data-create-button]:not([disabled])').first()
  if (!(await button.isVisible().catch(() => false))) return null
  await button.click()
  const dialog = page.locator('[role=dialog]').last()
  if (!(await dialog.waitFor({ timeout: 3000 }).then(() => true, () => false))) return null
  await page.waitForTimeout(400)
  return dialog.evaluate((el) => {
    const paper = el.closest('.MuiDialog-paper') ?? el
    const rect = paper.getBoundingClientRect()
    const actions = paper.querySelector('.MuiDialogActions-root')
    const vw = document.documentElement.clientWidth
    const vh = window.innerHeight
    return {
      title: (paper.querySelector('h2')?.innerText ?? '').trim(),
      fillsScreen: rect.left <= 1 && rect.top <= 1 && rect.right >= vw - 1 && rect.bottom >= vh - 1,
      // a dialog without footer (ViewDialog) has nothing to place
      footerAtBottom: actions ? actions.getBoundingClientRect().bottom >= vh - 1 : true,
    }
  })
}

async function visit(context, base, profile, target, width, shotDir) {
  const page = await context.newPage()
  await pinProfile(page, profile)
  await page.setViewportSize({ width, height: 800 })
  try {
    await page.goto(`${base}${target}`, { waitUntil: 'domcontentloaded' })
    await page.waitForSelector('h1', { timeout: 15000 }).catch(() => {})
    await settle(page, 600)
    const m = await page.evaluate(measureInPage)
    const file = `${profile}${target.replace(/[^a-z0-9]+/gi, '-')}__${width}`
    if (shotDir) await page.screenshot({ path: path.join(shotDir, `${file}.png`) })
    m.dialog = width === PHONE_WIDTH && m.createButton ? await openDialog(page) : null
    if (shotDir && m.dialog) await page.screenshot({ path: path.join(shotDir, `${file}__dialog.png`) })
    return { profile, path: target, url: page.url().replace(base, ''), width, ...m }
  } finally {
    await page.close()
  }
}

async function main() {
  const opts = args(process.argv.slice(2))
  const shotDir = opts.out && opts.shots ? path.join(opts.out, 'shots') : null
  if (shotDir) await mkdir(shotDir, { recursive: true })
  const browser = await launch(opts.chromium)
  const results = []
  try {
    for (const profile of opts.profiles) {
      const context = await schemeContext(browser, 'light', { width: PHONE_WIDTH })
      const queue = [...(opts.only ?? PAGES[profile] ?? [])]
      for (const extra of EXTRA) if (extra.profile === profile && !opts.only) queue.push(extra.path)
      const seen = new Set()
      while (queue.length) {
        const target = queue.shift()
        if (seen.has(target)) continue
        seen.add(target)
        // A page that does not load is a finding of its own, not the end of the pass.
        const safeVisit = (width, dir) =>
          visit(context, opts.base, profile, target, width, dir).catch((error) => ({
            profile, path: target, url: target, width, contentTop: null, overflowX: 0, tables: [],
            wrappedLabels: [], cutOff: [], tabs: [], createButton: null, dialog: null,
            loadError: String(error.message ?? error).split('\n')[0],
          }))
        const phone = await safeVisit(PHONE_WIDTH, shotDir)
        const reflow = await safeVisit(REFLOW_WIDTH, null)
        for (const tab of phone.tabs) if (!seen.has(tab) && !opts.only) queue.push(tab)
        for (const m of [phone, reflow]) {
          m.violations = violations(m, m.width)
          results.push(m)
        }
        const flag = phone.violations.length || reflow.violations.length ? 'FAIL' : 'ok  '
        console.log(
          `${flag} ${profile.padEnd(7)} ${phone.url.padEnd(48)} top ${String(phone.contentTop).padStart(4)}` +
            `  ${[...phone.violations, ...reflow.violations.map((v) => `320: ${v}`)].join('; ')}`,
        )
      }
      await context.close()
    }
  } finally {
    await browser.close()
  }
  const phones = results.filter((r) => r.width === PHONE_WIDTH)
  const tops = phones.map((r) => r.contentTop).filter((t) => t !== null)
  const failed = results.filter((r) => r.violations.length)
  console.log(
    `\n${phones.length} pages at ${PHONE_WIDTH} px, content top ${tops.length ? `${Math.min(...tops)}-${Math.max(...tops)} px` : 'not measured'}` +
      ` (limit ${CONTENT_TOP_MAX}); ${failed.length} measurements with findings`,
  )
  if (opts.out) {
    await mkdir(opts.out, { recursive: true })
    await writeFile(path.join(opts.out, 'mobile-pass.jsonl'), results.map((r) => JSON.stringify(r)).join('\n') + '\n')
  }
  process.exitCode = failed.length ? 1 : 0
}

await main()
