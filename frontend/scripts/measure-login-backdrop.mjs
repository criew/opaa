// Measures the bundled sign-in backdrop for the contrast proof in
// src/features/auth/components/AuthLayout.contrast.test.ts: decodes the image with ffmpeg,
// composites the panel scrim over every pixel exactly like compositeOver() does, and reports the
// pixel on which the brand name and the claim reach their lowest contrast ratio, together with the
// file's SHA-256. The test pins those values; re-run this after replacing the image.
// Usage: node scripts/measure-login-backdrop.mjs [image] [scrimOpacity]
//   defaults: src/features/auth/assets/login-backdrop.webp and 0.6
import { createHash } from 'node:crypto'
import { readFileSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

/** navyRoles.bg1 in src/shared/theme/tokens.ts; the test asserts the two still agree. */
const GROUND = [0x01, 0x21, 0x42]
const WHITE = [255, 255, 255]
/** LOGIN_CLAIM_OPACITY in AuthLayout.tsx; the test asserts the two still agree. */
const CLAIM_OPACITY = 0.78

const image =
  process.argv[2] ??
  fileURLToPath(new URL('../src/features/auth/assets/login-backdrop.webp', import.meta.url))
const scrim = Number.parseFloat(process.argv[3] ?? '0.6')
if (!Number.isFinite(scrim) || scrim < 0 || scrim > 1) {
  console.error(`scrim opacity must lie in 0..1, got ${process.argv[3]}`)
  process.exit(2)
}

const decoded = spawnSync(
  'ffmpeg',
  ['-v', 'error', '-i', image, '-f', 'rawvideo', '-pix_fmt', 'rgb24', '-'],
  { maxBuffer: 1024 * 1024 * 256 },
)
if (decoded.error || decoded.status !== 0) {
  console.error('ffmpeg could not decode the image:', decoded.error ?? decoded.stderr.toString())
  process.exit(1)
}
const pixels = decoded.stdout

const channel = (value) => {
  const v = value / 255
  return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4
}
const luminance = ([r, g, b]) => 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
const over = (top, bottom, alpha) =>
  top.map((value, i) => Math.round(value * alpha + bottom[i] * (1 - alpha)))
const contrast = (a, b) => {
  const la = luminance(a)
  const lb = luminance(b)
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
}
const hex = (rgb) => `#${rgb.map((v) => v.toString(16).padStart(2, '0')).join('')}`

let worstName = { ratio: Number.POSITIVE_INFINITY, pixel: null }
let worstClaim = { ratio: Number.POSITIVE_INFINITY, pixel: null }
let brightest = { luminance: -1, pixel: null }
for (let i = 0; i < pixels.length; i += 3) {
  const pixel = [pixels[i], pixels[i + 1], pixels[i + 2]]
  const ground = over(GROUND, pixel, scrim)
  const nameRatio = contrast(WHITE, ground)
  if (nameRatio < worstName.ratio) worstName = { ratio: nameRatio, pixel }
  const claimRatio = contrast(over(WHITE, ground, CLAIM_OPACITY), ground)
  if (claimRatio < worstClaim.ratio) worstClaim = { ratio: claimRatio, pixel }
  const lum = luminance(pixel)
  if (lum > brightest.luminance) brightest = { luminance: lum, pixel }
}

const result = {
  image,
  sha256: createHash('sha256').update(readFileSync(image)).digest('hex'),
  pixelCount: pixels.length / 3,
  scrimOpacity: scrim,
  ground: hex(GROUND),
  brightestPixel: hex(brightest.pixel),
  worstPixelForName: hex(worstName.pixel),
  minContrastName: Number(worstName.ratio.toFixed(3)),
  worstPixelForClaim: hex(worstClaim.pixel),
  minContrastClaim: Number(worstClaim.ratio.toFixed(3)),
}
console.log(JSON.stringify(result, null, 2))
