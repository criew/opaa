import { expect, type Page } from '@playwright/test'

/**
 * The edge rule of the set-up assistant in the open step:
 * - every field that fills its line (its outer box, so an end adornment such as the show/hide
 *   toggle counts) and every labelled list ends on the right edge of the primary action in the
 *   footer; in a pair such as server and port that is the last field of the line. A number field
 *   keeps its fixed width, and no field reaches beyond the action,
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
    .evaluateAll((elements) => {
      const boxes = elements.map((element) => element.getBoundingClientRect())
      return elements.map((element, index) => {
        const box = boxes[index]
        const control = element.closest('.MuiFormControl-root')
        return {
          name:
            element.querySelector('input, [role=combobox]')?.id ||
            element.getAttribute('aria-label') ||
            element.tagName,
          right: box.right,
          // A number field keeps its width (160 px) instead of filling the line.
          fillsLine: !control || control.classList.contains('MuiFormControl-fullWidth'),
          // Another field stands to the right in the same line: this one is the first of a pair.
          lastInLine: !boxes.some(
            (other, otherIndex) =>
              otherIndex !== index &&
              other.left >= box.right &&
              other.top < box.bottom &&
              other.bottom > box.top,
          ),
        }
      })
    })
  for (const edge of rightEdges) {
    expect(edge.right, `right edge of ${edge.name}`).toBeLessThanOrEqual(actionRight + 0.5)
    if (edge.lastInLine && (edge.fillsLine || edge.right > actionRight - 1)) {
      expect(edge.right, `right edge of ${edge.name}`).toBeCloseTo(actionRight, 0)
    }
  }
  // The rule above holds trivially without a field that fills its line; every step has one or a list.
  expect(
    rightEdges.some((edge) => edge.lastInLine && Math.abs(edge.right - actionRight) < 0.5) ||
      rightEdges.length === 0,
    'a field or list on the edge of the primary action',
  ).toBe(true)

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
