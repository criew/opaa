import { expect, type Page } from '@playwright/test'

/**
 * The edge rule of the set-up assistant in the open step:
 * - every field (its outer box, so an end adornment such as the show/hide toggle counts) and
 *   every labelled list ends on the right edge of the primary action in the footer,
 * - every hint or error below a field starts on the field's left edge,
 * - the page does not scroll sideways.
 */
export async function expectStepEdgesFlush(page: Page) {
  const form = page.locator('main form')
  const actionBox = await form.locator('button[type="submit"]').boundingBox()
  expect(actionBox, 'primary action').not.toBeNull()
  const actionRight = actionBox!.x + actionBox!.width

  const rightEdges = await form
    .locator('.MuiInputBase-root, ul[aria-label]')
    .evaluateAll((elements) =>
      elements.map((element) => ({
        name:
          element.querySelector('input, [role=combobox]')?.id ||
          element.getAttribute('aria-label') ||
          element.tagName,
        right: element.getBoundingClientRect().right,
      })),
    )
  for (const edge of rightEdges) {
    expect(edge.right, `right edge of ${edge.name}`).toBeCloseTo(actionRight, 0)
  }

  const leftEdges = await form.locator('.MuiFormHelperText-root').evaluateAll((helpers) =>
    helpers.map((helper) => {
      const field = helper.parentElement?.querySelector('.MuiInputBase-root')
      return {
        name: helper.id,
        left: helper.getBoundingClientRect().left,
        fieldLeft: field ? field.getBoundingClientRect().left : null,
      }
    }),
  )
  for (const edge of leftEdges) {
    expect(edge.fieldLeft, `field of ${edge.name}`).not.toBeNull()
    expect(edge.left, `left edge of ${edge.name}`).toBeCloseTo(edge.fieldLeft!, 0)
  }

  const overflow = await page
    .locator('html')
    .evaluate((root) => root.scrollWidth - root.clientWidth)
  expect(overflow, 'horizontal overflow').toBeLessThanOrEqual(0)
}
