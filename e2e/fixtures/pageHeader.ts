import type { Locator, Page } from '@playwright/test'

/**
 * The create button of the page header („Neuer Zugang", „Neuer Katalogeintrag", „Neuer Prompt";
 * guidelines 5.1). An empty list repeats it with the same label inside its empty state
 * (`data-empty-state`, guidelines 5.7); this locator leaves that copy out, so a scenario reaches
 * the same button whether the list is empty or not.
 */
export function headerCreateButton(page: Page, name: string): Locator {
  return page
    .getByRole('button', { name, exact: true })
    .and(page.locator(':not([data-empty-state] *)'))
}
