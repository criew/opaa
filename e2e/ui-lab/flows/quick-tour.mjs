import { sleep } from '../lib/browser.mjs'
import { clickButton, clickByName, firstVisible, settle } from '../lib/ui.mjs'

/**
 * Quick tour for new users (UI lab #2312). One type, `chat`: the empty new chat of the private
 * area, where every variant begins. Areas: `start` (what a first visit shows), `step` (one station
 * further - for D the tour started from the invitation), `last` (the final station), `again` (the
 * profile menu with the entry that starts the tour again).
 */

export const description = 'Quick-Tour: erster Besuch, ein Schritt weiter, letzter Schritt, Profilmenü'
export const DEFAULT_SCENARIOS = ['small', 'default', 'empty']
export const QUICK_SCENARIOS = ['small']
export const AREAS = ['start', 'step', 'last', 'again']

export function jobs({ scenarios }) {
  return scenarios.map((scenario) => ({ type: 'chat', scenario }))
}

const LAST = /^(Fertig|Erste Frage stellen)$/

async function openChat(page, ctx, scenario) {
  await page.goto(`${ctx.base}/spaces/space-personal/chats/new?mockScenario=${scenario}`, {
    waitUntil: 'domcontentloaded',
  })
  await page.waitForSelector('main', { timeout: 15000 }).catch(() => {})
  await settle(page, 800)
  // Variants A and B open on their own after the chat has laid out.
  await sleep(700)
}

export async function run(page, job, ctx) {
  const want = (area) => !ctx.areas || ctx.areas.includes(area)
  await openChat(page, ctx, job.scenario)
  if (want('start')) await ctx.shoot('start')

  // D starts its tour from the invitation; the other variants have no such button.
  await clickButton(page, 'Rundgang starten', { exact: true, wait: 700 })
  const moved = await clickButton(page, 'Weiter', { exact: true, wait: 700 })
  if (want('step')) {
    if (moved) await ctx.shoot('step')
    else ctx.notFound('step', 'no tour to page through')
  }

  if (want('last')) {
    let last = await firstVisible(page.getByRole('button', { name: LAST }))
    for (let i = 0; !last && moved && i < 8; i++) {
      await clickButton(page, 'Weiter', { exact: true, wait: 500 })
      last = await firstVisible(page.getByRole('button', { name: LAST }))
    }
    if (last) await ctx.shoot('last')
    else ctx.notFound('last', 'no last station')
  }

  if (want('again')) {
    // The last station's own button ends the tour; Escape would depend on where the focus sits.
    await clickByName(page, ['button'], LAST, { wait: 500 })
    const narrow = (page.viewportSize()?.width ?? 1280) < 900
    if (narrow) await clickButton(page, 'Menü öffnen', { exact: true, wait: 600 })
    if (await clickButton(page, 'Profil und Einstellungen', { exact: true, wait: 600 })) {
      await ctx.shoot('again')
    } else ctx.notFound('again', 'no profile menu')
  }
}
