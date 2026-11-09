import { settle } from '../lib/ui.mjs'

/**
 * Agent setup (UI lab #3670): the empty setup and its validation, the delivered agent „Recherche“
 * filled and as detail, the choice in the chat ('@' list or own button, then the chosen agent)
 * and the type choice of „Neuer Katalogeintrag“.
 */

export const description = 'Agenten: Einrichtung leer und ausgefüllt, Detail, Wahl im Chat, Typwahl'
export const DEFAULT_SCENARIOS = ['default', 'scale', 'empty', 'norights']
export const QUICK_SCENARIOS = ['default', 'scale']
export const AREAS = ['setup', 'validation', 'filled', 'detail', 'pick', 'chosen', 'newentry']
const TYPES = ['new', 'recherche', 'chat', 'catalog']

export function jobs({ scenarios, types }) {
  return scenarios.flatMap((scenario) => (types ?? TYPES).map((type) => ({ type, scenario })))
}

async function open(page, ctx, route, scenario) {
  const sep = route.includes('?') ? '&' : '?'
  await page.goto(`${ctx.base}${route}${sep}mockScenario=${scenario}`, { waitUntil: 'domcontentloaded' })
  await page.waitForSelector('main, [role=main]', { timeout: 15000 }).catch(() => {})
  await settle(page, 900)
}

export async function run(page, job, ctx) {
  const want = (area) => ctx.areas.includes(area)
  if (job.type === 'new') {
    await open(page, ctx, '/agents/new', job.scenario)
    if (want('setup')) await ctx.shoot('setup')
    if (want('validation')) {
      const submit = page.getByRole('button', { name: /^(Agent anlegen|Weiter)$/ }).last()
      if ((await submit.count()) === 0 || (await submit.isDisabled())) {
        ctx.notFound('validation', 'no enabled submit')
      } else {
        await submit.click()
        await settle(page, 500)
        await ctx.shoot('validation')
      }
    }
  } else if (job.type === 'recherche') {
    if (want('filled')) {
      await open(page, ctx, '/agents/agent-recherche/edit', job.scenario)
      await ctx.shoot('filled')
    }
    if (want('detail')) {
      await open(page, ctx, '/agents/agent-recherche', job.scenario)
      await ctx.shoot('detail')
    }
  } else if (job.type === 'chat') {
    await open(page, ctx, '/chat', job.scenario)
    await settle(page, 800)
    const button = page.getByRole('button', { name: 'Agent wählen' })
    if ((await button.count()) > 0) {
      await button.first().click()
    } else {
      const input = page.getByRole('combobox', { name: /Nachricht eingeben/ })
      await input.click()
      await input.pressSequentially('@')
    }
    await settle(page, 600)
    if (want('pick')) await ctx.shoot('pick')
    const choice = page
      .getByRole('option', { name: /Recherche/ })
      .or(page.getByRole('menuitem', { name: /Recherche/ }))
    // the measurement may have closed the menu (resize, focus); open it again
    if ((await choice.count()) === 0 && (await button.count()) > 0) {
      await page.keyboard.press('Escape').catch(() => {})
      await button.first().click()
      await settle(page, 400)
    }
    if ((await choice.count()) === 0) {
      ctx.notFound('chosen', 'no agent Recherche offered')
      return
    }
    await choice.first().click()
    await settle(page, 600)
    if (want('chosen')) await ctx.shoot('chosen')
  } else if (job.type === 'catalog') {
    await open(page, ctx, '/catalog/new', job.scenario)
    if (want('newentry')) await ctx.shoot('newentry')
  }
}
