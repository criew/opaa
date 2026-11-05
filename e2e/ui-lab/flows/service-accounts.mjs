import { sleep } from '../lib/browser.mjs'
import { clickButton, fillByLabel, firstVisible, settle } from '../lib/ui.mjs'

/**
 * Service accounts of Fachverfahren (epic #3587). Type `admin`: the list in the administration,
 * one account opened, the empty create form submitted, a token issued with its one-time value.
 * Type `library`: the section „Fachverfahren mit Zugriff“ in the Reiter „Freigaben“ and its add
 * step. Works for every variant: the list may sit in the tab of the external AI tools or in a tab
 * of its own, an account may open as a side panel, a view or in place.
 */

export const description = 'Dienstkonten: Liste, Detail, Anlegen, Token; Bibliothek: Fachverfahren mit Zugriff'
export const DEFAULT_SCENARIOS = ['small', 'default', 'scale', 'empty', 'failed', 'partial']
export const QUICK_SCENARIOS = ['small', 'scale']
export const AREAS = ['list', 'detail', 'create-validation', 'token-value', 'section', 'add']

const LIBRARY = 'library-nextcloud-projekte'

export function jobs({ scenarios }) {
  return scenarios.flatMap((scenario) => ['admin', 'library'].map((type) => ({ type, scenario })))
}

async function scrollToText(page, pattern) {
  await page.getByText(pattern).first().waitFor({ state: 'visible', timeout: 6000 }).catch(() => {})
  const el = await firstVisible(page.getByText(pattern))
  if (!el) return false
  await el.scrollIntoViewIfNeeded().catch(() => {})
  await el.evaluate((node) => node.scrollIntoView({ block: 'start' })).catch(() => {})
  await sleep(200)
  return true
}

async function openAdmin(page, ctx, scenario) {
  await page.goto(`${ctx.base}/admin/connections/external-tools?mockScenario=${scenario}`, {
    waitUntil: 'domcontentloaded',
  })
  await settle(page, 600)
  // variant B: a tab of its own
  const tab = await firstVisible(page.getByRole('tab', { name: /^Dienstkonten$/ }))
  if (tab) {
    await tab.click().catch(() => {})
    await settle(page, 600)
  }
  return scrollToText(page, /^Dienstkonten|Eigene Zugänge für Fachverfahren/)
}

async function openFirstAccount(page) {
  const list = await firstVisible(page.getByRole('list', { name: 'Dienstkonten' }))
  const table = await firstVisible(page.getByRole('table', { name: 'Dienstkonten' }))
  const scope = table ?? list
  if (!scope) return false
  const target =
    (await firstVisible(scope.getByRole('link'))) ?? (await firstVisible(scope.getByRole('button')))
  if (!target) return false
  await target.click().catch(() => {})
  await settle(page, 700)
  await sleep(900)
  // an account opened in place (variant C) is shown from its row on
  await target.evaluate((node) => node.scrollIntoView({ block: 'start' })).catch(() => {})
  return true
}

async function runAdmin(page, job, ctx) {
  const want = (area) => !ctx.areas || ctx.areas.includes(area)
  const found = await openAdmin(page, ctx, job.scenario)
  if (!found) {
    for (const area of ['list', 'detail', 'create-validation', 'token-value']) {
      if (want(area)) ctx.notFound(area, 'no service account area')
    }
    return
  }
  if (want('list')) await ctx.shoot('list')

  if (want('detail') && ['small', 'default', 'scale', 'partial'].includes(job.scenario)) {
    if (await openFirstAccount(page)) await ctx.shoot('detail')
    else ctx.notFound('detail', 'no account to open')
  }

  if (want('token-value') && ['small', 'default'].includes(job.scenario)) {
    if (await clickButton(page, /^Token ausstellen$/)) {
      await fillByLabel(page, [/^Name$/], 'Produktion 2')
      await clickButton(page, /^Ausstellen$/, { wait: 900 })
      if (await firstVisible(page.getByTestId('service-account-token-value'))) await ctx.shoot('token-value')
      else ctx.notFound('token-value', 'value not shown')
      await clickButton(page, /^Kopiert, schließen$/)
    } else ctx.notFound('token-value', 'no issue action')
  }

  if (want('create-validation') && ['small', 'default'].includes(job.scenario)) {
    await openAdmin(page, ctx, job.scenario)
    if (await clickButton(page, /^Dienstkonto anlegen$/)) {
      await clickButton(page, /^(Anlegen|Weiter)$/, { wait: 500 })
      await ctx.shoot('create-validation')
      await page.keyboard.press('Escape').catch(() => {})
    } else ctx.notFound('create-validation', 'no create action')
  }
}

async function runLibrary(page, job, ctx) {
  const want = (area) => !ctx.areas || ctx.areas.includes(area)
  await page.goto(`${ctx.base}/libraries/${LIBRARY}?tab=freigaben&mockScenario=${job.scenario}`, {
    waitUntil: 'domcontentloaded',
  })
  await settle(page, 900)
  const found = await scrollToText(page, /^Fachverfahren( mit Zugriff)?$/)
  if (!found) {
    for (const area of ['section', 'add']) if (want(area)) ctx.notFound(area, 'no section')
    return
  }
  if (want('section')) await ctx.shoot('section')
  if (want('add') && ['small', 'default', 'scale'].includes(job.scenario)) {
    // A: „Aufnehmen“ opens a dialog; B: „Verwalten“ opens the list with the add row; C: inline
    const opened =
      (await clickButton(page, /^Verwalten/)) ||
      (await clickButton(page, /^Aufnehmen$/, { wait: 600 }))
    if (opened) {
      await settle(page, 400)
      await ctx.shoot('add')
      await page.keyboard.press('Escape').catch(() => {})
    } else ctx.notFound('add', 'no add action')
  }
}

export async function run(page, job, ctx) {
  if (job.type === 'admin') return runAdmin(page, job, ctx)
  return runLibrary(page, job, ctx)
}
