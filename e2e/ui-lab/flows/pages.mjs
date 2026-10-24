import { settle } from '../lib/ui.mjs'

/**
 * Any area without a dedicated flow: each path of `--pages` is loaded per scenario and shot once
 * after it settles. The path's slug ("/admin/users" → "admin-users") is the `type` of the shot,
 * the area is always "page".
 */

export const description = 'Beliebige Seiten: je Pfad aus --pages ein Aufruf und eine Aufnahme'
export const DEFAULT_SCENARIOS = ['default', 'small', 'scale', 'empty', 'failed']
export const QUICK_SCENARIOS = ['small', 'scale']
export const AREAS = ['page']

const slug = (p) => p.replace(/^\/+|\/+$/g, '').replace(/[^a-z0-9]+/gi, '-').toLowerCase() || 'root'

export function jobs({ pages, scenarios }) {
  if (!pages?.length) throw new Error('flow "pages" needs --pages /path,/other/path')
  return scenarios.flatMap((scenario) => pages.map((p) => ({ type: slug(p), path: p, scenario })))
}

export async function run(page, job, ctx) {
  const sep = job.path.includes('?') ? '&' : '?'
  await page.goto(`${ctx.base}${job.path}${sep}mockScenario=${job.scenario}`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('main, [role=main]', { timeout: 15000 }).catch(() => {})
  await settle(page, 800)
  await ctx.shoot('page')
}
