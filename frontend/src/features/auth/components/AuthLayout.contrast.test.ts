import { describe, expect, it } from 'vitest'
import {
  LOGIN_BACKDROP_DEFAULT_SCRIM_OPACITY,
  LOGIN_BACKDROP_SCRIM_OPACITY,
  LOGIN_CLAIM_OPACITY,
} from './AuthLayout'
import {
  TEXT_CONTRAST_MINIMUM,
  compositeOver,
  contrastRatio,
  lightenHex,
} from '@/shared/utils/contrast'
import { navyRoles } from '@/shared/theme/tokens'
// The shipped bytes as a data URI - the one way to get at an asset's content without Node APIs,
// which the application's type configuration deliberately leaves out.
import bundledBackdropInline from '../assets/login-backdrop.webp?inline'

/**
 * What `node scripts/measure-login-backdrop.mjs` reported for the shipped file: the pixel on which
 * the claim reaches its lowest contrast under the default scrim, and the file's checksum. The
 * script composites every pixel exactly like compositeOver() does, so the pixel pinned here is the
 * worst case of the whole image, not a sample. A replaced image fails the checksum until it has been
 * measured again and these values updated together.
 */
const BUNDLED_BACKDROP = {
  sha256: 'c7b2110edaeaf0dcf6872af1f221374986366c4ca7050b92f4021ac8da180496',
  /** The script's own constants; the proof below only holds while they match the theme. */
  measuredAgainst: { ground: '#012142', claimOpacity: 0.78 },
  worstPixel: '#ADB295',
}

/**
 * Browsers and the measuring decoder may round a lossy pixel a few levels apart. The proof is
 * therefore also run on the worst pixel lightened by this much, so a decoder that lands slightly
 * brighter still leaves the threshold intact.
 */
const DECODER_TOLERANCE = 0.03

/** SHA-256 of the bytes behind a base64 data URI, as lowercase hex. */
async function sha256OfDataUri(dataUri: string): Promise<string> {
  const base64 = dataUri.slice(dataUri.indexOf(',') + 1)
  const bytes = Uint8Array.from(atob(base64), (char) => char.charCodeAt(0))
  const digest = await crypto.subtle.digest('SHA-256', bytes)
  return Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('')
}

/**
 * Text sits on top of an uploaded background image. This application does not know which image
 * an installation uploads — so only the worst case can be checked: a pure white and a pure black
 * image under the fixed scrim. If the text reaches the 4.5:1 from
 * docs/design/accessibility.md there, it reaches it on every image.
 */
describe('Schleier über dem Hintergrundbild der Anmeldeseite', () => {
  const extremes = { 'ein rein weißes Bild': '#FFFFFF', 'ein rein schwarzes Bild': '#000000' }

  function ground(image: string): string {
    const composited = compositeOver(navyRoles.bg1, image, LOGIN_BACKDROP_SCRIM_OPACITY)
    expect(composited).not.toBeNull()
    return composited as string
  }

  function ratio(foreground: string, background: string): number {
    const value = contrastRatio(foreground, background)
    expect(value).not.toBeNull()
    return value as number
  }

  for (const [name, image] of Object.entries(extremes)) {
    it(`trägt den Produktnamen über ${name}`, () => {
      expect(ratio(navyRoles.fg1, ground(image))).toBeGreaterThanOrEqual(TEXT_CONTRAST_MINIMUM)
    })

    it(`trägt den Claim über ${name}`, () => {
      // The claim's opacity acts on the already composited ground.
      const grundfarbe = ground(image)
      const claim = compositeOver(navyRoles.fg1, grundfarbe, LOGIN_CLAIM_OPACITY) as string

      expect(ratio(claim, grundfarbe)).toBeGreaterThanOrEqual(TEXT_CONTRAST_MINIMUM)
    })
  }
})

/**
 * The bundled backdrop is the one image this application does know. Its scrim is lighter than
 * the worst-case one above, and the proof runs against the brightest pixel the shipped file
 * actually contains instead of against pure white.
 */
describe('Schleier über dem mitgelieferten Hintergrundbild', () => {
  function ground(image: string): string {
    const composited = compositeOver(navyRoles.bg1, image, LOGIN_BACKDROP_DEFAULT_SCRIM_OPACITY)
    expect(composited).not.toBeNull()
    return composited as string
  }

  function ratio(foreground: string, background: string): number {
    const value = contrastRatio(foreground, background)
    expect(value).not.toBeNull()
    return value as number
  }

  it('ist leichter als der Schleier über hochgeladenen Bildern', () => {
    expect(LOGIN_BACKDROP_DEFAULT_SCRIM_OPACITY).toBeLessThan(LOGIN_BACKDROP_SCRIM_OPACITY)
  })

  it('wurde gegen die Datei gemessen, die ausgeliefert wird', async () => {
    expect(bundledBackdropInline.startsWith('data:image/webp;base64,')).toBe(true)
    expect(
      await sha256OfDataUri(bundledBackdropInline),
      'Bild ersetzt? node scripts/measure-login-backdrop.mjs ausführen und BUNDLED_BACKDROP nachziehen',
    ).toBe(BUNDLED_BACKDROP.sha256)
  })

  it('wurde gegen die Farben gemessen, die das Theme tatsächlich verwendet', () => {
    expect(navyRoles.bg1.toLowerCase()).toBe(BUNDLED_BACKDROP.measuredAgainst.ground)
    expect(LOGIN_CLAIM_OPACITY).toBe(BUNDLED_BACKDROP.measuredAgainst.claimOpacity)
  })

  const worstPixels = {
    'dem hellsten Pixel': BUNDLED_BACKDROP.worstPixel,
    'dem hellsten Pixel mit Dekoder-Toleranz': lightenHex(
      BUNDLED_BACKDROP.worstPixel,
      DECODER_TOLERANCE,
    ) as string,
  }

  for (const [name, image] of Object.entries(worstPixels)) {
    it(`trägt den Produktnamen über ${name}`, () => {
      expect(ratio(navyRoles.fg1, ground(image))).toBeGreaterThanOrEqual(TEXT_CONTRAST_MINIMUM)
    })

    it(`trägt den Claim über ${name}`, () => {
      const grundfarbe = ground(image)
      const claim = compositeOver(navyRoles.fg1, grundfarbe, LOGIN_CLAIM_OPACITY) as string

      expect(ratio(claim, grundfarbe)).toBeGreaterThanOrEqual(TEXT_CONTRAST_MINIMUM)
    })
  }
})
