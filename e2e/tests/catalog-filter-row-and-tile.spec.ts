import { expect, test } from '../fixtures/auth'
import {
  catalogEntry,
  createKnowledgeLibraryViaApi,
  escapeRegExp,
  searchCatalog,
} from '../fixtures/promptLibraries'
import type { Locator, Page } from '@playwright/test'

/**
 * Filterzeile und Kachel des Katalogs (#2180): Suche, Typgruppe und Favoriten stehen auf breiten
 * Bildschirmen in einer Zeile und brechen auf schmalen als Einheit um; Stern und „⋯“ heben nur
 * sich selbst hervor, nie die Kachel. Beides hängt an echtem Layout und echten CSS-Zuständen
 * (`:hover`, `:focus`, `:has()`), die jsdom nicht berechnet.
 */

/** The vertical centre of an element - two controls of one row share it. */
async function centreY(locator: Locator): Promise<number> {
  const box = await locator.boundingBox()
  expect(box, 'Element ohne Layout').not.toBeNull()
  return box!.y + box!.height / 2
}

async function borderColor(tile: Locator): Promise<string> {
  return tile.evaluate((element) => {
    // The suite compiles without the DOM library; this runs in the browser.
    const browser = globalThis as unknown as {
      getComputedStyle(node: typeof element): { borderTopColor: string }
    }
    return browser.getComputedStyle(element).borderTopColor
  })
}

/** Takes the pointer off every tile, so only focus and press states can still colour one. */
async function movePointerAway(page: Page): Promise<void> {
  await page.mouse.move(1, 1)
}

test.describe('Katalog: Filterzeile und Kachel (#2180)', () => {
  test('die Filterzeile steht breit in einer Zeile und bricht schmal als Einheit um', async ({
    regularUserPage: page,
  }) => {
    const search = page.getByRole('searchbox', { name: 'Suchen' })
    const types = page.getByRole('group', { name: 'Typ' })
    const favorites = page.getByRole('button', { name: 'Favoriten' })

    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto('/catalog')
    await expect(favorites).toBeVisible()
    // #2207: the type group carries its name "Typ" without a visible title, at every width.
    await expect(page.getByText('Typ', { exact: true })).toHaveCount(0)
    const row = await centreY(search)
    expect(Math.abs((await centreY(types)) - row)).toBeLessThan(4)
    expect(Math.abs((await centreY(favorites)) - row)).toBeLessThan(4)

    await page.setViewportSize({ width: 600, height: 800 })
    await expect(favorites).toBeVisible()
    const searchBox = (await search.boundingBox())!
    const typesBox = (await types.boundingBox())!
    expect(typesBox.y, 'Typgruppe steht unter der Suche').toBeGreaterThan(
      searchBox.y + searchBox.height,
    )
    expect(
      Math.abs((await centreY(favorites)) - (await centreY(types))),
      'Favoriten-Chip steht neben der Typgruppe, nicht allein',
    ).toBeLessThan(4)

    // Reflow width (WCAG 1.4.10): the type buttons drop their icons, the group keeps its name
    // "Typ", and the unit runs with less padding.
    await page.setViewportSize({ width: 320, height: 800 })
    await expect(favorites).toBeVisible()
    expect(
      Math.abs((await centreY(favorites)) - (await centreY(types))),
      'Favoriten-Chip steht bei 320 px neben der Typgruppe',
    ).toBeLessThan(4)
    const narrowTypes = (await types.boundingBox())!
    const narrowFavorites = (await favorites.boundingBox())!
    expect(
      narrowFavorites.x + narrowFavorites.width,
      'Typgruppe und Favoriten passen in die Breite',
    ).toBeLessThanOrEqual(320)
    expect(narrowTypes.x).toBeGreaterThanOrEqual(0)
  })

  test('Stern und „⋯“ heben die Kachel nicht hervor, der Fokus auf dem Titel schon', async ({
    regularUserPage: page,
  }) => {
    const name = `E2E Kachelzustand ${Date.now()}`
    await createKnowledgeLibraryViaApi('dev-user', { name })
    await page.setViewportSize({ width: 1280, height: 800 })
    await searchCatalog(page, name)
    const tile = catalogEntry(page, name)
    await expect(tile).toBeVisible()

    await movePointerAway(page)
    const resting = await borderColor(tile)

    await page.getByRole('button', { name: `„${name}“ als Favorit markieren` }).click()
    await expect(
      page.getByRole('button', { name: `„${name}“ aus den Favoriten entfernen` }),
    ).toBeFocused()
    await movePointerAway(page)
    expect(await borderColor(tile), 'Kachel nach Klick auf den Stern').toBe(resting)

    const more = page.getByRole('button', {
      name: new RegExp(`Weitere Aktionen für .${escapeRegExp(name)}`),
    })
    await more.click()
    await expect(page.getByRole('menu')).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(page.getByRole('menu')).toBeHidden()
    await expect(more).toBeFocused()
    await movePointerAway(page)
    expect(await borderColor(tile), 'Kachel nach dem „⋯“-Menü').toBe(resting)

    // The keyboard path to the tile's own control still marks the whole tile.
    await page.keyboard.press('Tab')
    await expect(page.getByRole('link', { name: new RegExp(escapeRegExp(name)) })).toBeFocused()
    expect(await borderColor(tile), 'Kachel mit Tastaturfokus auf dem Titel').not.toBe(resting)
  })
})
