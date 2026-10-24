#!/usr/bin/env node
/*
 * UI-Lab videos: records click paths through the frontend in MSW mock mode as MP4.
 *
 *   node ui-lab/record-flows.mjs --base http://localhost:5191 --out <dir>
 *        [--flows flows/library-videos.mjs] [--types a,b] [--kinds ok,viele,fehler]
 *        [--scheme light|dark] [--width 1280] [--height 800] [--pause 700] [--keep-webm]
 *        [--chromium <path>]
 *
 * The video matrix: a flows module exports `TYPES` (the areas, e.g. connectors), `KINDS` (kind ->
 * mock scenario, by default ok -> small, viele -> scale, fehler -> failed) and one
 * `run(h, { type, kind, scenario })` that plays the flow of a type in that kind. Every
 * type × kind becomes <type>__<kind>__<scheme>.mp4. Each video gets its own browser
 * context (scheme set before the first load) with Playwright's recordVideo, a visible mouse
 * pointer with a click ring, and an optional caption line. `h` (goto, click, type, visible, pause,
 * caption) moves the pointer to an element
 * before every click, pauses between steps and never throws on a missing element; a missing
 * element is logged and the step skipped. ffmpeg (PATH or FFMPEG) turns the WebM into H.264 MP4;
 * <out>/manifest.json lists every video with name, type, scenario and scheme.
 */
import fs from 'node:fs'
import path from 'node:path'
import { execFileSync } from 'node:child_process'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { launch, schemeContext, sleep } from './lib/browser.mjs'
import { firstVisible, settle } from './lib/ui.mjs'

const HERE = path.dirname(fileURLToPath(import.meta.url))

function parseArgs(argv) {
  const args = {
    base: 'http://localhost:5191',
    out: path.join(HERE, 'videos'),
    flows: path.join(HERE, 'flows', 'library-videos.mjs'),
    scheme: 'light',
    width: 1280,
    height: 800,
    pause: 700,
  }
  const count = (a, v) => {
    const n = Number(v)
    if (!Number.isInteger(n) || n < 0) throw new Error(`${a} needs a whole number, got ${v}`)
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
    else if (a === '--flows') args.flows = path.resolve(next())
    else if (a === '--types') args.types = next().split(',')
    else if (a === '--kinds') args.kinds = next().split(',')
    else if (a === '--scheme') args.scheme = next()
    else if (a === '--width') args.width = count(a, next())
    else if (a === '--height') args.height = count(a, next())
    else if (a === '--pause') args.pause = count(a, next())
    else if (a === '--keep-webm') args.keepWebm = true
    else if (a === '--chromium') args.chromium = next()
    else if (a === '--help' || a === '-h') {
      console.log(fs.readFileSync(fileURLToPath(import.meta.url), 'utf8').split('*/')[0])
      process.exit(0)
    } else throw new Error(`unknown argument ${a}`)
  }
  return args
}

/** Pointer, click ring and caption, drawn above the page; pointer-events none, so clicks pass. */
function overlayScript() {
  const install = () => {
    if (document.getElementById('ui-lab-pointer')) return
    const style = document.createElement('style')
    style.textContent = `
      #ui-lab-pointer { position: fixed; z-index: 2147483647; left: -40px; top: -40px; width: 22px; height: 22px;
        margin: -2px 0 0 -2px; pointer-events: none; }
      #ui-lab-ring { position: fixed; z-index: 2147483646; width: 36px; height: 36px; margin: -18px 0 0 -18px;
        border: 3px solid #d32f2f; border-radius: 50%; pointer-events: none; opacity: 0; }
      #ui-lab-caption { position: fixed; z-index: 2147483647; left: 50%; bottom: 24px; transform: translateX(-50%);
        max-width: 80vw; padding: 8px 16px; border-radius: 6px; background: rgba(20, 20, 20, 0.88); color: #fff;
        font: 600 18px/1.4 system-ui, sans-serif; pointer-events: none; display: none; }`
    document.documentElement.appendChild(style)
    const pointer = document.createElement('div')
    pointer.id = 'ui-lab-pointer'
    pointer.innerHTML =
      '<svg width="22" height="22" viewBox="0 0 22 22"><path d="M2 2 L2 18 L6.5 13.5 L9.5 20 L12.5 18.7 L9.6 12.4 L16 12.4 Z" fill="#111" stroke="#fff" stroke-width="1.5" stroke-linejoin="round"/></svg>'
    const ring = document.createElement('div')
    ring.id = 'ui-lab-ring'
    const caption = document.createElement('div')
    caption.id = 'ui-lab-caption'
    document.documentElement.append(pointer, ring, caption)
    const last = window.__uiLabPointer ?? { x: -40, y: -40 }
    pointer.style.left = `${last.x}px`
    pointer.style.top = `${last.y}px`
    window.addEventListener('mousemove', (e) => {
      window.__uiLabPointer = { x: e.clientX, y: e.clientY }
      pointer.style.left = `${e.clientX}px`
      pointer.style.top = `${e.clientY}px`
    }, true)
    window.addEventListener('mousedown', (e) => {
      ring.style.left = `${e.clientX}px`
      ring.style.top = `${e.clientY}px`
      ring.animate([{ opacity: 1, transform: 'scale(0.5)' }, { opacity: 0, transform: 'scale(1.4)' }], { duration: 500 })
    }, true)
  }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', install)
  else install()
}

function helpers(page, args, log) {
  let pointer = { x: args.width / 2, y: args.height / 2 }
  const resolve = async (target) => (typeof target === 'string' ? firstVisible(page.locator(target)) : firstVisible(target))
  const moveTo = async (el) => {
    await el.scrollIntoViewIfNeeded().catch(() => {})
    const box = await el.boundingBox()
    if (!box) return false
    const to = { x: box.x + box.width / 2, y: box.y + box.height / 2 }
    const steps = Math.max(8, Math.round(Math.hypot(to.x - pointer.x, to.y - pointer.y) / 25))
    await page.mouse.move(to.x, to.y, { steps })
    pointer = to
    return true
  }
  const h = {
    page,
    out: args.out,
    log,
    pause: (ms = args.pause) => sleep(ms),
    /** Whether the target is visible within `timeout` ms. */
    async visible(target, timeout = 1500) {
      const locator = typeof target === 'string' ? page.locator(target) : target
      return locator.first().waitFor({ state: 'visible', timeout }).then(() => true, () => false)
    },
    async goto(p) {
      await page.goto(`${args.base}${p}`, { waitUntil: 'domcontentloaded' })
      await settle(page, 600)
      await page.mouse.move(pointer.x, pointer.y)
    },
    /** Moves to the element, clicks it and pauses; false (and a log line) when it is missing. */
    async click(target, label = String(target)) {
      const el = await resolve(target)
      if (!el || !(await moveTo(el))) {
        log(`  missing: ${label}`)
        return false
      }
      await sleep(250)
      await page.mouse.down()
      await sleep(80)
      await page.mouse.up()
      await settle(page, 200)
      await sleep(args.pause)
      return true
    },
    /** Clicks the field and types `text` visibly. */
    async type(target, text, label = String(target)) {
      const el = await resolve(target)
      if (!el || !(await moveTo(el))) {
        log(`  missing: ${label}`)
        return false
      }
      await el.click().catch(() => {})
      await el.fill('').catch(() => {})
      await page.keyboard.type(text, { delay: 45 })
      await sleep(args.pause / 2)
      return true
    },
    /** Shows a caption line at the bottom of the video; an empty text hides it. */
    async caption(text) {
      await page.evaluate((t) => {
        const el = document.getElementById('ui-lab-caption')
        if (!el) return
        el.textContent = t
        el.style.display = t ? 'block' : 'none'
      }, text).catch(() => {})
      if (text) await sleep(args.pause)
    },
  }
  return h
}

function toMp4(webm, mp4) {
  const ffmpeg = process.env.FFMPEG || 'ffmpeg'
  execFileSync(ffmpeg, ['-y', '-loglevel', 'error', '-i', webm, '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-preset', 'veryfast', '-crf', '28', '-movflags', '+faststart', mp4])
}

const ARGS = parseArgs(process.argv.slice(2))
const T0 = Date.now()
const log = (...a) => console.log(`[${((Date.now() - T0) / 1000).toFixed(0).padStart(5)}s]`, ...a)
const flows = await import(pathToFileURL(ARGS.flows).href)
const KINDS = flows.KINDS ?? { ok: 'small', viele: 'scale', fehler: 'failed' }
const pick = (wanted, known, what) => {
  const unknown = (wanted ?? []).filter((x) => !known.includes(x))
  if (unknown.length) throw new Error(`unknown ${what} ${unknown.join(',')}; known: ${known.join(', ')}`)
  return wanted ?? known
}
const types = pick(ARGS.types, flows.TYPES, 'type(s)')
const kinds = pick(ARGS.kinds, Object.keys(KINDS), 'kind(s)')
const chosen = types.flatMap((type) => kinds.map((kind) => ({ name: `${type}__${kind}`, type, kind, scenario: KINDS[kind] })))
fs.mkdirSync(ARGS.out, { recursive: true })
const raw = path.join(ARGS.out, '.webm')
const browser = await launch(ARGS.chromium)
const done = []
const manifestFile = path.join(ARGS.out, 'manifest.json')
const manifest = fs.existsSync(manifestFile) ? JSON.parse(fs.readFileSync(manifestFile, 'utf8')) : []
for (const video of chosen) {
  log(`== ${video.name} (${video.scenario}, ${ARGS.scheme})`)
  const context = await schemeContext(browser, ARGS.scheme, {
    width: ARGS.width,
    recordVideo: { dir: raw, size: { width: ARGS.width, height: ARGS.height } },
  })
  await context.addInitScript(overlayScript)
  const page = await context.newPage()
  await page.setViewportSize({ width: ARGS.width, height: ARGS.height })
  const h = helpers(page, ARGS, log)
  try {
    await flows.run(h, { type: video.type, kind: video.kind, scenario: video.scenario })
    await h.pause(1200)
  } catch (e) {
    log(`  error: ${String(e.message).split('\n')[0]}`)
  }
  const recording = page.video()
  await context.close()
  const webm = await recording.path()
  const mp4 = path.join(ARGS.out, `${video.name}__${ARGS.scheme}.mp4`)
  if (!fs.existsSync(webm)) {
    log(`  no recording written; Playwright's ffmpeg is missing or incompatible (see README, "Videos")`)
    continue
  }
  try {
    toMp4(webm, mp4)
    if (ARGS.keepWebm) fs.renameSync(webm, mp4.replace(/\.mp4$/, '.webm'))
    else fs.rmSync(webm)
    done.push(path.basename(mp4))
    const entry = { file: path.basename(mp4), name: video.name, type: video.type, kind: video.kind, scenario: video.scenario, scheme: ARGS.scheme }
    manifest.splice(0, manifest.length, ...manifest.filter((m) => m.file !== entry.file), entry)
    log(`  -> ${mp4}`)
  } catch (e) {
    const kept = mp4.replace(/\.mp4$/, '.webm')
    fs.renameSync(webm, kept)
    log(`  ffmpeg failed (${String(e.message).split('\n')[0]}); WebM kept: ${kept}`)
  }
}
await browser.close()
fs.writeFileSync(manifestFile, JSON.stringify(manifest, null, 2))
fs.rmSync(raw, { recursive: true, force: true })
log(`done: ${done.length} of ${chosen.length} videos`)
