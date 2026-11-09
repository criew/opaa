// Lab-only: records the speech input variants as videos with captions and a visible cursor.
// Usage: node ui-lab/flows/speech-input-videos.mjs <outDir> [variant ...]
import { chromium } from '@playwright/test'
import { mkdirSync, renameSync } from 'node:fs'
import { join } from 'node:path'

const BASE = process.env.UI_LAB_BASE ?? 'http://localhost:5191'
const out = process.argv[2]
const only = process.argv.slice(3)
mkdirSync(out, { recursive: true })

const OVERLAY = `
(() => {
  const cap = document.createElement('div')
  cap.id = 'lab-caption'
  Object.assign(cap.style, { position: 'fixed', top: '14px', left: '50%', transform: 'translateX(-50%)',
    zIndex: 2147483647, background: '#3b1f5c', color: '#fff', font: '600 18px/1.3 system-ui, sans-serif',
    padding: '10px 18px', borderRadius: '10px', border: '2px solid #f6c445', pointerEvents: 'none',
    maxWidth: '80vw', textAlign: 'center', display: 'none' })
  const dot = document.createElement('div')
  dot.id = 'lab-cursor'
  Object.assign(dot.style, { position: 'fixed', width: '22px', height: '22px', borderRadius: '50%',
    background: 'rgba(246,196,69,.55)', border: '2px solid #3b1f5c', zIndex: 2147483647,
    pointerEvents: 'none', transform: 'translate(-50%,-50%)', left: '-50px', top: '-50px',
    transition: 'transform .12s' })
  const add = () => { document.body.append(cap, dot) }
  if (document.body) add(); else addEventListener('DOMContentLoaded', add)
  addEventListener('mousemove', (e) => { dot.style.left = e.clientX + 'px'; dot.style.top = e.clientY + 'px' }, true)
  addEventListener('mousedown', () => { dot.style.transform = 'translate(-50%,-50%) scale(.7)' }, true)
  addEventListener('mouseup', () => { dot.style.transform = 'translate(-50%,-50%)' }, true)
  window.__caption = (t) => { cap.textContent = t; cap.style.display = t ? 'block' : 'none' }
})()`

function helpers(page) {
  const caption = async (text, ms = 1400) => {
    await page.evaluate((t) => window.__caption?.(t), text)
    await page.waitForTimeout(ms)
  }
  const click = async (locator) => {
    const box = await locator.boundingBox()
    if (!box) throw new Error('no box')
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2, { steps: 18 })
    await page.waitForTimeout(250)
    await locator.click()
    await page.waitForTimeout(250)
  }
  const field = page.getByRole('combobox', { name: /Nachricht|Frage|Eingabe/i }).first()
  /** Selects "Bau Antrag" in a text field and types the correction. */
  const correct = async (input) => {
    await click(input)
    await input.evaluate((el) => {
      const i = el.value.indexOf('Bau Antrag')
      el.focus()
      if (i >= 0) el.setSelectionRange(i, i + 'Bau Antrag'.length)
    })
    await page.waitForTimeout(700)
    await page.keyboard.type('Bauantrag', { delay: 90 })
    await page.waitForTimeout(600)
  }
  const waitAnswerDone = async () => {
    await page.waitForTimeout(1200)
    await page
      .getByRole('button', { name: 'Antwort abbrechen' })
      .waitFor({ state: 'detached', timeout: 30_000 })
      .catch(() => {})
    await page.waitForTimeout(800)
  }
  const btn = (name) => page.getByRole('button', { name, exact: true })
  return { caption, click, correct, field, waitAnswerDone, btn }
}

const SPEAK_MS = 6400

const flows = {
  // Current state: mic, stop, text lands in the field, correct, send.
  async ist(page, h, live) {
    await h.caption('Ist-Stand · ' + (live ? 'Live' : 'Diktat') + ': Mikrofon neben Senden')
    await h.caption('1 · Starten, sprechen, verwerfen', 900)
    await h.click(h.btn('Spracheingabe starten'))
    await page.waitForTimeout(2600)
    await h.click(h.btn('Aufnahme verwerfen'))
    await h.caption('Verworfen, nichts im Feld')
    await h.caption('2 · Starten, sprechen, beenden', 900)
    await h.click(h.btn('Spracheingabe starten'))
    await page.waitForTimeout(SPEAK_MS)
    await h.click(h.btn('Aufnahme beenden'))
    await h.caption(live ? 'Text steht sofort im Feld' : 'Text wird erkannt …', live ? 1400 : 2000)
    await h.caption('3 · Korrigieren im Feld', 500)
    await h.correct(h.field)
    await h.caption('4 · Senden (direkt senden gibt es nicht)', 900)
    await h.click(h.btn('Senden'))
    await h.waitAnswerDone()
  },
  async a(page, h, live) {
    await h.caption('A · Im Feld · ' + (live ? 'Live' : 'Diktat'))
    await h.caption('1 · Starten: Wellenform ersetzt das Feld', 900)
    await h.click(h.btn('Spracheingabe starten'))
    await page.waitForTimeout(2600)
    await h.caption('Verwerfen mit ✕', 600)
    await h.click(h.btn('Aufnahme verwerfen'))
    await h.caption('2 · Direkt senden: Senden stoppt und schickt', 900)
    await h.click(h.btn('Spracheingabe starten'))
    await page.waitForTimeout(SPEAK_MS)
    await h.click(h.btn('Senden'))
    await h.waitAnswerDone()
    await h.caption('3 · Mit ✓ ins Feld übernehmen, korrigieren, senden', 900)
    await h.click(h.btn('Spracheingabe starten'))
    await page.waitForTimeout(SPEAK_MS)
    await h.click(h.btn('Fertig, Text ins Eingabefeld'))
    await page.waitForTimeout(live ? 800 : 1900)
    await h.correct(h.field)
    await h.click(h.btn('Senden'))
    await h.waitAnswerDone()
  },
  async b(page, h, live) {
    await h.caption('B · Aufnahmekarte · ' + (live ? 'Live' : 'Diktat'))
    await h.caption('1 · Starten: Karte über dem Feld, verwerfen mit Papierkorb', 900)
    await h.click(h.btn('Spracheingabe starten'))
    await page.waitForTimeout(2600)
    await h.click(h.btn('Aufnahme verwerfen'))
    await h.caption('2 · Pause und weiter, beenden, in der Karte korrigieren, senden', 900)
    await h.click(h.btn('Spracheingabe starten'))
    await page.waitForTimeout(2300)
    await h.click(h.btn('Pause'))
    await page.waitForTimeout(1500)
    await h.click(h.btn('Fortsetzen'))
    await page.waitForTimeout(SPEAK_MS - 2000)
    await h.click(h.btn('Aufnahme beenden'))
    await page.waitForTimeout(live ? 800 : 1900)
    await h.correct(page.getByRole('textbox', { name: 'Erkannter Text' }))
    await h.click(page.getByRole('button', { name: 'Senden' }).first())
    await h.waitAnswerDone()
    await h.caption('3 · Oder: in die Eingabe übernehmen und dort weiterschreiben', 900)
    await h.click(h.btn('Spracheingabe starten'))
    await page.waitForTimeout(SPEAK_MS)
    await h.click(h.btn('Aufnahme beenden'))
    await page.waitForTimeout(live ? 800 : 1900)
    await h.click(h.btn('In Eingabe übernehmen'))
    await page.waitForTimeout(1200)
  },
  async c(page, h, live) {
    await h.caption('C · Direkt ins Feld · ' + (live ? 'Live' : 'Diktat'))
    await h.caption('1 · Starten: Mikrofon wird rot, Text läuft ins Feld; Esc verwirft', 900)
    await h.click(h.btn('Spracheingabe starten'))
    await page.waitForTimeout(2600)
    await page.keyboard.press('Escape')
    await h.caption('Verworfen, Feld wie vorher')
    await h.caption('2 · Direkt senden: Senden stoppt und schickt', 900)
    await h.click(h.btn('Spracheingabe starten'))
    await page.waitForTimeout(SPEAK_MS)
    await h.click(h.btn('Senden'))
    await h.waitAnswerDone()
    await h.caption('3 · Mikrofon beendet, korrigieren, senden', 900)
    await h.click(h.btn('Spracheingabe starten'))
    await page.waitForTimeout(SPEAK_MS)
    await h.click(h.btn('Diktat beenden'))
    await page.waitForTimeout(live ? 800 : 1900)
    await h.correct(h.field)
    await h.click(h.btn('Senden'))
    await h.waitAnswerDone()
  },
}

const RUNS = [
  ['ist-batch', 'none', 'ist', false],
  ['ist-live', 'ist-live', 'ist', true],
  ['a-batch', 'a-batch', 'a', false],
  ['a-live', 'a-live', 'a', true],
  ['b-batch', 'b-batch', 'b', false],
  ['b-live', 'b-live', 'b', true],
  ['c-batch', 'c-batch', 'c', false],
  ['c-live', 'c-live', 'c', true],
]

const browser = await chromium.launch({ executablePath: process.env.UI_LAB_CHROMIUM })
for (const [name, variant, flow, live] of RUNS) {
  if (only.length && !only.includes(name)) continue
  const context = await browser.newContext({
    viewport: { width: 1100, height: 700 },
    recordVideo: { dir: out, size: { width: 1100, height: 700 } },
  })
  await context.addInitScript(OVERLAY)
  const page = await context.newPage()
  page.on('pageerror', (e) => console.log(name, 'ERR', e.message))
  await page.goto(`${BASE}/spaces/space-personal/chats/new?variant=${variant}`)
  await page.getByRole('button', { name: 'Spracheingabe starten' }).waitFor({ timeout: 20_000 })
  await page.waitForTimeout(600)
  try {
    await flows[flow](page, helpers(page), live)
  } catch (e) {
    console.log(name, 'FLOW FAILED', e.message)
  }
  await page.evaluate(() => window.__caption?.('Ende'))
  await page.waitForTimeout(1000)
  const video = page.video()
  await context.close()
  renameSync(await video.path(), join(out, `${name}.webm`))
  console.log('recorded', name)
}
await browser.close()
