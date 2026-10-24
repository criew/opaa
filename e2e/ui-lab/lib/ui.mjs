import { sleep } from './browser.mjs'

/**
 * Lenient element helpers for flows that must survive differently designed variants: elements are
 * found by role and German label, and a missing element yields `false`/`null` instead of throwing.
 */

const ACTION_TIMEOUT = 2500

export async function firstVisible(locator) {
  const n = Math.min(await locator.count().catch(() => 0), 30)
  for (let i = 0; i < n; i++) {
    const el = locator.nth(i)
    if (await el.isVisible().catch(() => false)) return el
  }
  return null
}

/** Clicks the first visible element of any of `roles` whose accessible name matches `name`. */
export async function clickByName(page, roles, name, { wait = 400, exact = false } = {}) {
  for (const role of roles) {
    const el = await firstVisible(page.getByRole(role, { name, exact }))
    if (!el) continue
    try {
      await el.click({ timeout: ACTION_TIMEOUT })
      await sleep(wait)
      return true
    } catch {
      /* try the next role */
    }
  }
  return false
}

export const clickButton = (page, name, opts) => clickByName(page, ['button', 'link'], name, opts)

/** Fills the first visible, editable field whose label matches one of `labels`. */
export async function fillByLabel(page, labels, value) {
  for (const label of [].concat(labels)) {
    const el = await firstVisible(page.getByLabel(label))
    if (!el) continue
    try {
      if (!(await el.isEditable())) continue
      await el.fill(value, { timeout: ACTION_TIMEOUT })
      await sleep(80)
      return true
    } catch {
      /* next label */
    }
  }
  return false
}

/** Ticks up to `n` visible unchecked options, tree items or checkboxes that look like source objects. */
export async function selectSome(page, n) {
  const EXCLUDE = /sofort|Indizierung|bestätige|Dienstkonto|Team|Path-Style|alle|Alle|Erste|Pfad-Stil|Diagnose|Benachrichtig/
  let picked = 0
  const options = page.getByRole('option')
  const optionCount = Math.min(await options.count().catch(() => 0), 200)
  for (let i = 0; i < optionCount && picked < n; i++) {
    const o = options.nth(i)
    if (!(await o.isVisible().catch(() => false))) continue
    if ((await o.getAttribute('aria-selected')) === 'true') continue
    await o.click({ timeout: 1000 }).catch(() => {})
    picked++
  }
  if (picked) {
    await page.keyboard.press('Escape').catch(() => {})
    return picked
  }
  for (const role of ['treeitem', 'checkbox']) {
    const boxes = page.getByRole(role)
    const count = Math.min(await boxes.count().catch(() => 0), 400)
    for (let i = 0; i < count && picked < n; i++) {
      const b = boxes.nth(i)
      if (!(await b.isVisible().catch(() => false))) continue
      const name =
        (await b.getAttribute('aria-label').catch(() => null)) ??
        (await b.evaluate((el) => el.closest('label')?.innerText ?? el.parentElement?.innerText ?? '').catch(() => ''))
      if (EXCLUDE.test(name || '')) continue
      if (await b.isChecked().catch(() => null)) continue
      if (!(await b.isEnabled().catch(() => false))) continue
      await b.click({ timeout: 1000 }).catch(() => {})
      picked++
    }
    if (picked) return picked
  }
  return picked
}

/** Waits until the network is quiet for a moment (the mocks answer in-page). */
export async function settle(page, ms = 500) {
  await page.waitForLoadState('networkidle', { timeout: 4000 }).catch(() => {})
  await sleep(ms)
}

export async function headingText(page) {
  return page
    .evaluate(() =>
      [...document.querySelectorAll('h1,h2')]
        .filter((h) => h.offsetParent !== null)
        .map((h) => h.innerText.trim())
        .join(' | '),
    )
    .catch(() => '')
}

export async function alertTexts(page) {
  return page
    .evaluate(() =>
      [...document.querySelectorAll('[role=alert], .MuiAlert-root, .Mui-error.MuiFormHelperText-root')]
        .filter((e) => e.offsetParent !== null)
        .map((e) => e.innerText.trim().slice(0, 140))
        .join(' / '),
    )
    .catch(() => '')
}
