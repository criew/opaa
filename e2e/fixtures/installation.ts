import { expect, type Page } from '@playwright/test'

/**
 * The edge rule of the set-up assistant: in the open step, every field (its outer box, so an end
 * adornment such as the show/hide toggle counts) and every list ends on the right edge of the
 * primary action in the footer, and the page does not scroll sideways.
 */
export async function expectStepEdgesFlush(page: Page) {
  const form = page.locator('main form')
  const actionBox = await form.locator('button[type="submit"]').boundingBox()
  expect(actionBox, 'primary action').not.toBeNull()
  const actionRight = actionBox!.x + actionBox!.width

  const edges = await form.locator('.MuiInputBase-root, ul').evaluateAll((elements) =>
    elements.map((element) => ({
      name:
        element.querySelector('input, [role=combobox]')?.id ||
        element.getAttribute('aria-label') ||
        element.tagName,
      right: element.getBoundingClientRect().right,
    })),
  )
  for (const edge of edges) {
    expect(edge.right, `right edge of ${edge.name}`).toBeCloseTo(actionRight, 0)
  }

  const overflow = await page
    .locator('html')
    .evaluate((root) => root.scrollWidth - root.clientWidth)
  expect(overflow, 'horizontal overflow').toBeLessThanOrEqual(0)
}
