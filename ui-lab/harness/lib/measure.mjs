import fs from 'node:fs'
import path from 'node:path'
import AxeBuilder from '@axe-core/playwright'
import { VIEWPORT_HEIGHT, appliedScheme, sleep } from './browser.mjs'

/**
 * Count budgets of docs/design/ui-pruefliste.md, section "Zählbudgets". A shot over budget lists
 * the exceeded keys in `budgetViolations`. The line budget applies from 1280 px on, where a wrapped
 * help text is the text's fault rather than the viewport's; the word budget applies in the
 * scenarios "small" and "default" only, where object names cannot dominate the count.
 */
export const BUDGETS = {
  helpTextsPerForm: 3,
  helpTextLines: 1,
  uiWords: 150,
}
const WORD_BUDGET_SCENARIOS = ['small', 'default']

const AXE_TAGS = ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa', 'best-practice']

/*
 * Page functions below share `isOpen` and take the last open [role=dialog], the topmost when
 * dialogs stack. A closed MUI drawer keeps its paper mounted with role=dialog and visibility
 * hidden; it must neither count as open nor replace <main>. `isOpen` is repeated inline because
 * page functions are serialised without their module scope.
 */

/** Runs in the page: layout, text and control metrics of the main area (or the open dialog). */
const PAGE_METRICS = () => {
  const isOpen = (d) => { const cs = getComputedStyle(d); const r = d.getBoundingClientRect(); return cs.visibility !== 'hidden' && cs.display !== 'none' && r.width > 0 && r.height > 0 && !d.closest('[aria-hidden=true]') }
  const main = [...document.querySelectorAll('[role=dialog]')].filter(isOpen).at(-1) ?? document.querySelector('main') ?? document.body
  const visible = (el) => {
    const r = el.getBoundingClientRect()
    const cs = getComputedStyle(el)
    return r.width > 0 && r.height > 0 && cs.visibility !== 'hidden' && cs.display !== 'none'
  }
  const scrollers = [document.documentElement, document.querySelector('main')].filter(Boolean)
  const overflow = scrollers.some((el) => el.scrollWidth > el.clientWidth + 1)
  let culprit = null
  if (overflow) {
    const vw = document.documentElement.clientWidth
    for (const el of document.querySelectorAll('main *')) {
      const r = el.getBoundingClientRect()
      if (r.right > vw + 1 && visible(el)) {
        culprit = `${el.tagName.toLowerCase()}.${String(el.className).split(' ')[0]} right=${Math.round(r.right)}`
        break
      }
    }
  }
  // content height: the viewport plus the largest hidden overflow of a scroll container (dialog first)
  const dialogEl = [...document.querySelectorAll('[role=dialog]')].filter(isOpen).at(-1)
  let hiddenOverflow = document.documentElement.scrollHeight - window.innerHeight
  for (const el of dialogEl ? [dialogEl, ...dialogEl.querySelectorAll('*')] : document.querySelectorAll('*')) {
    if (!/(auto|scroll)/.test(getComputedStyle(el).overflowY)) continue
    hiddenOverflow = Math.max(hiddenOverflow, el.scrollHeight - el.clientHeight)
  }
  const docHeight = window.innerHeight + Math.max(0, hiddenOverflow)
  // text cut off without an ellipsis (overflow hidden/clip), e.g. a long compound at 320 px
  let clipped = 0
  for (const el of main.querySelectorAll('*')) {
    if (el.scrollWidth <= el.clientWidth + 1 || !el.clientWidth) continue
    const cs = getComputedStyle(el)
    if (!/(hidden|clip)/.test(cs.overflowX) || cs.textOverflow === 'ellipsis' || !visible(el)) continue
    if (!el.innerText || !el.innerText.trim()) continue
    clipped++
  }
  const SUBMIT = /^(Weiter|Bibliothek anlegen|Anlegen|Erstellen|Speichern|Übernehmen|Prüfen)$/
  const buttons = [...document.querySelectorAll('button, [role=button]')].filter(visible)
  const disabledSubmits = buttons
    .filter((b) => SUBMIT.test((b.innerText || '').trim()) && (b.disabled || b.getAttribute('aria-disabled') === 'true'))
    .map((b) => b.innerText.trim())
  const alerts = [...new Set([...document.querySelectorAll('[role=alert], .MuiAlert-root')].filter(visible))].map((el) => {
    const m = /MuiAlert-(?:standard|outlined|filled|color)(Error|Warning|Info|Success)/.exec(el.className)
    return m ? m[1].toLowerCase() : el.getAttribute('role') === 'alert' ? 'role-alert' : 'unknown'
  })
  const severities = alerts.reduce((acc, s) => ((acc[s] = (acc[s] ?? 0) + 1), acc), {})
  const FOCUSABLE =
    'a[href], button:not([disabled]), input:not([disabled]):not([type=hidden]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"]), [contenteditable=true]'
  const focusable = [...document.querySelectorAll(FOCUSABLE)].filter((el) => visible(el) && el.tabIndex >= 0).length
  // words of the UI itself: text in lists, trees, grids, tables, description lists and the labels
  // of checkboxes and radios is data (object names, stored values) and not counted in uiWords
  const DATA = [
    '[role=list]', '[role=listbox]', '[role=tree]', '[role=grid]', '[role=table]', '[role=row]', '[role=option]',
    'table', 'ul', 'ol', 'dl', 'label:has(input[type=checkbox])', 'label:has(input[type=radio])',
  ].join(', ')
  const sizes = new Set()
  const colors = new Set()
  const walker = document.createTreeWalker(main, NodeFilter.SHOW_TEXT)
  let words = 0
  let uiWords = 0
  while (walker.nextNode()) {
    const text = walker.currentNode.textContent.trim()
    if (!text) continue
    const el = walker.currentNode.parentElement
    if (!el || !visible(el)) continue
    const cs = getComputedStyle(el)
    sizes.add(cs.fontSize)
    colors.add(cs.color)
    const n = text.split(/\s+/).filter(Boolean).length
    words += n
    if (!el.closest(DATA)) uiWords += n
  }
  // help texts: MUI helper lines that are not field errors, grouped by their form
  const lineCount = (el) => {
    const cs = getComputedStyle(el)
    const lh = parseFloat(cs.lineHeight) || parseFloat(cs.fontSize) * 1.2
    return Math.max(1, Math.round(el.getBoundingClientRect().height / lh))
  }
  const helpEls = [...main.querySelectorAll('.MuiFormHelperText-root:not(.Mui-error)')].filter((el) => visible(el) && el.innerText.trim())
  const perForm = new Map()
  for (const el of helpEls) {
    const form = el.closest('form') ?? main
    perForm.set(form, (perForm.get(form) ?? 0) + 1)
  }
  const helpLines = helpEls.map(lineCount)
  // upper edge of the tab bar in document coordinates (pruefliste-detail D1)
  const tablist = document.querySelector('[role=tablist]')
  const tabsTop = tablist ? Math.round(tablist.getBoundingClientRect().top + window.scrollY) : null
  return {
    tabsTop,
    title: document.title,
    url: location.pathname + location.search,
    overflowX: overflow,
    overflowCulprit: culprit,
    docHeight,
    clippedText: clipped,
    disabledSubmits: disabledSubmits.length,
    disabledSubmitLabels: disabledSubmits,
    alertCount: alerts.length,
    alertSeverities: severities,
    invalidFields: [...main.querySelectorAll('[aria-invalid=true]')].filter(visible).length,
    focusable,
    fontSizes: sizes.size,
    colors: colors.size,
    words,
    uiWords,
    helpTexts: helpEls.length,
    helpTextsPerForm: Math.max(0, ...perForm.values()),
    helpTextMaxLines: Math.max(0, ...helpLines),
    helpTextsMultiLine: helpLines.filter((n) => n > 1).length,
    headings: [...main.querySelectorAll('h1,h2,h3')].filter(visible).map((h) => h.innerText.trim()).slice(0, 12),
  }
}

export function budgetViolations(metrics, width, scenario) {
  const over = []
  if (metrics.helpTextsPerForm > BUDGETS.helpTextsPerForm) over.push('helpTextsPerForm')
  if (width >= 1280 && metrics.helpTextMaxLines > BUDGETS.helpTextLines) over.push('helpTextLines')
  if (WORD_BUDGET_SCENARIOS.includes(scenario) && metrics.uiWords > BUDGETS.uiWords) over.push('uiWords')
  return over
}

export function pageMetrics(page) {
  return page.evaluate(PAGE_METRICS).catch((e) => ({ error: String(e).slice(0, 200) }))
}

/**
 * axe on the whole document. The light scheme runs WCAG 2.0–2.2 A/AA plus best practice
 * (target-size on, region off); `contrastOnly` runs just color-contrast, for the further schemes.
 * Each contrast finding keeps its colours and ratio so a report can be checked by eye.
 */
export async function runAxe(page, { contrastOnly = false } = {}) {
  try {
    const builder = new AxeBuilder({ page }).options({ resultTypes: ['violations'] })
    if (contrastOnly) builder.withRules(['color-contrast'])
    else builder.withTags(AXE_TAGS).disableRules(['region']).options({ resultTypes: ['violations'], rules: { 'target-size': { enabled: true } } })
    const result = await builder.analyze()
    const violations = result.violations.map((v) => ({ id: v.id, impact: v.impact, nodes: v.nodes.length, sample: v.nodes[0]?.target?.join(' ') }))
    const contrast = result.violations.find((v) => v.id === 'color-contrast')
    const contrastSamples = (contrast?.nodes ?? []).slice(0, 5).map((n) => {
      const data = n.any?.[0]?.data ?? {}
      return { target: n.target?.join(' '), fg: data.fgColor, bg: data.bgColor, ratio: data.contrastRatio }
    })
    return { violations, contrastViolations: contrast?.nodes.length ?? 0, contrastSamples }
  } catch (e) {
    return { error: String(e).slice(0, 200) }
  }
}

/** Grows the viewport so inner scroll containers (AppShell main, dialogs) fit, for __full. */
async function growForFull(page, width) {
  for (let i = 0; i < 4; i++) {
    const extra = await page.evaluate(() => {
      // with an open dialog only its own scroll area counts; the page behind stays as it is
      const isOpen = (d) => { const cs = getComputedStyle(d); const r = d.getBoundingClientRect(); return cs.visibility !== 'hidden' && cs.display !== 'none' && r.width > 0 && r.height > 0 && !d.closest('[aria-hidden=true]') }
      const dialog = [...document.querySelectorAll('[role=dialog]')].filter(isOpen).at(-1)
      let max = 0
      for (const el of dialog ? [dialog, ...dialog.querySelectorAll('*')] : document.querySelectorAll('*')) {
        if (!/(auto|scroll)/.test(getComputedStyle(el).overflowY)) continue
        max = Math.max(max, el.scrollHeight - el.clientHeight)
      }
      return dialog ? max : Math.max(max, document.documentElement.scrollHeight - window.innerHeight)
    })
    if (extra <= 2) break
    const vp = page.viewportSize()
    await page.setViewportSize({ width, height: Math.min(vp.height + extra + 8, 16000) })
    await sleep(200)
  }
}

/** The whole page, or with an open dialog the dialog itself rather than the dimmed page behind it. */
async function fullScreenshot(page, file) {
  const marked = await page.evaluate(() => {
    const isOpen = (d) => { const cs = getComputedStyle(d); const r = d.getBoundingClientRect(); return cs.visibility !== 'hidden' && cs.display !== 'none' && r.width > 0 && r.height > 0 && !d.closest('[aria-hidden=true]') }
    const dialogs = [...document.querySelectorAll('[role=dialog]')].filter(isOpen)
    document.querySelectorAll('[data-ui-lab-full]').forEach((d) => d.removeAttribute('data-ui-lab-full'))
    dialogs.at(-1)?.setAttribute('data-ui-lab-full', '')
    return dialogs.length > 0
  })
  const target = marked ? page.locator('[data-ui-lab-full]') : page
  await target.screenshot({ path: file }).catch(() => {})
}

async function saveScroll(page) {
  return page.evaluate(() => {
    const saved = []
    document.querySelectorAll('*').forEach((el, i) => {
      if (el.scrollTop || el.scrollLeft) saved.push([i, el.scrollTop, el.scrollLeft])
    })
    return { saved, x: window.scrollX, y: window.scrollY }
  })
}

async function restoreScroll(page, state) {
  await page.evaluate((state) => {
    const all = document.querySelectorAll('*')
    all.forEach((el) => {
      if (el.scrollTop) el.scrollTop = 0
    })
    for (const [i, top, left] of state.saved) {
      if (all[i]) {
        all[i].scrollTop = top
        all[i].scrollLeft = left
      }
    }
    window.scrollTo(state.x, state.y)
    document.activeElement?.blur?.()
  }, state)
}

/** The first 40 tab stops from the top of the page (or of the open dialog's focus trap). */
export async function tabTrace(page) {
  const scroll = await saveScroll(page)
  await page.evaluate(() => {
    document.activeElement?.blur?.()
    const isOpen = (d) => { const cs = getComputedStyle(d); const r = d.getBoundingClientRect(); return cs.visibility !== 'hidden' && cs.display !== 'none' && r.width > 0 && r.height > 0 && !d.closest('[aria-hidden=true]') }
    if (![...document.querySelectorAll('[role=dialog]')].some(isOpen)) window.scrollTo(0, 0)
  })
  const stops = []
  let hidden = 0
  let obscured = 0
  const probe = () =>
    page.evaluate(() => {
      const el = document.activeElement
      if (!el || el === document.body) return null
      const r = el.getBoundingClientRect()
      const cs = getComputedStyle(el)
      // opacity is no criterion: MUI radios, checkboxes and switches focus a transparent native input
      const isHidden = r.width < 2 || r.height < 2 || cs.visibility === 'hidden'
      let isObscured = false
      if (!isHidden) {
        const x = Math.min(Math.max(r.left + r.width / 2, 0), window.innerWidth - 1)
        const y = Math.min(Math.max(r.top + r.height / 2, 0), window.innerHeight - 1)
        const top = document.elementFromPoint(x, y)
        isObscured = !!top && !el.contains(top) && !top.contains(el) && !(el.labels && [...el.labels].some((l) => l.contains(top)))
        if (r.bottom < 0 || r.top > window.innerHeight) isObscured = true
      }
      const labelled = el.getAttribute('aria-labelledby')
      const name =
        el.getAttribute('aria-label') ||
        (labelled && labelled.split(' ').map((id) => document.getElementById(id)?.innerText ?? '').join(' ')) ||
        (el.labels && el.labels[0]?.innerText) ||
        el.getAttribute('placeholder') ||
        (el.innerText || el.value || '').trim()
      return {
        role: el.getAttribute('role') || el.tagName.toLowerCase() + (el.type ? `:${el.type}` : ''),
        name: String(name).replace(/\s+/g, ' ').slice(0, 60),
        hidden: isHidden,
        obscured: isObscured,
      }
    })
  for (let i = 0; i < 40; i++) {
    await page.keyboard.press('Tab')
    let s = await probe()
    // a skip link or a field scrolled into view may still be moving: look again once
    if (s?.obscured) {
      await sleep(250)
      s = await probe()
    }
    if (!s) continue
    if (s.hidden) hidden++
    if (s.obscured) obscured++
    stops.push(s)
  }
  await restoreScroll(page, scroll).catch(() => {})
  return { tabStops: stops, focusHidden: hidden, focusObscured: obscured }
}

/**
 * Screenshots and metrics into one output directory; metrics.jsonl starts empty on every run, so a
 * repeated run into the same directory never mixes old and new lines. `shoot` takes the current state in every
 * width of the run, in the scheme of the page's context; one metrics.jsonl line per screenshot.
 * The tab trace runs in the light scheme only, full axe in light, contrast-only axe in dark.
 */
export function createRecorder({ out, widths, noAxe = false, noFull = false, log = console.log }) {
  fs.mkdirSync(out, { recursive: true })
  const metricsFile = path.join(out, 'metrics.jsonl')
  fs.writeFileSync(metricsFile, '')
  const record = (line) => fs.appendFileSync(metricsFile, JSON.stringify(line) + '\n')
  let shots = 0

  async function shoot(page, { area, type, scenario, scheme }, { prepare, ...extra } = {}) {
    const shown = await appliedScheme(page)
    if (shown && shown !== scheme) log(`  WARNING ${area} ${type} ${scenario}: requested ${scheme}, page shows ${shown}`)
    for (const width of widths) {
      await page.setViewportSize({ width, height: VIEWPORT_HEIGHT })
      await sleep(250)
      // a flow may bring its subject back into view after the resize, e.g. an expanded run
      if (prepare) await prepare(page, width)
      const name = `${area}__${type}__${scenario}__${scheme}__${width}`
      await page.screenshot({ path: path.join(out, `${name}.png`) }).catch((e) => log('  screenshot failed', e.message))
      const metrics = await pageMetrics(page)
      const axe = noAxe ? null : await runAxe(page, { contrastOnly: scheme !== 'light' })
      if (!noFull) {
        await growForFull(page, width)
        await fullScreenshot(page, path.join(out, `${name}__full.png`))
        await page.setViewportSize({ width, height: VIEWPORT_HEIGHT })
        await sleep(150)
      }
      const trace = scheme === 'light' ? await tabTrace(page).catch(() => null) : null
      record({
        shot: `${name}.png`,
        area,
        type,
        scenario,
        scheme,
        appliedScheme: shown,
        width,
        status: 'ok',
        ...extra,
        ...metrics,
        budgetViolations: metrics.error ? [] : budgetViolations(metrics, width, scenario),
        axe,
        ...(trace ?? {}),
      })
      shots++
    }
    await page.setViewportSize({ width: widths[0], height: VIEWPORT_HEIGHT })
    log(`  shot ${area} ${type} ${scenario} ${scheme}`)
  }

  function notFound({ area, type, scenario, scheme }, reason) {
    for (const width of widths) record({ shot: null, area, type, scenario, scheme, width, status: 'not-found', reason })
    log(`  not-found ${area} ${type} ${scenario} ${scheme}: ${reason}`)
  }

  return { shoot, notFound, record, shotCount: () => shots }
}
