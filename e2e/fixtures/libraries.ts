import type { APIRequestContext, Page } from '@playwright/test'
import { expect, test } from './auth'

/**
 * Shared building blocks for scenarios that create throwaway knowledge libraries and clean them up
 * again over the API (#424, #471, #547, #1944). Kept here once instead of copied per spec file -
 * the same three helpers had grown a third copy before #1944's review caught it.
 */

/**
 * Mirrors frontend/src/services/devAuth.ts's DEV_USER_HEADER. Not imported from there: e2e/ is its
 * own npm package with no dependency on frontend/src (see e2e/package.json).
 */
export const DEV_USER_HEADER = 'X-OPAA-Dev-User'

/** Reads the library id LibraryCreatePage navigated to after a successful "Bibliothek anlegen". */
export function libraryIdFromCurrentUrl(page: Page): string {
  const match = page.url().match(/\/libraries\/([^/]+)$/)
  if (!match) {
    throw new Error(`Unexpected library detail URL after creation: ${page.url()}`)
  }
  return match[1]
}

/**
 * Deletes a library a scenario created, together with any documents it holds. Works for both
 * kinds: an UPLOAD library rejects DELETE while it still holds documents (ADR-0018), so those are
 * removed first; a connector library that never ran simply has none to list.
 */
export async function deleteLibraryCompletely(
  request: APIRequestContext,
  libraryId: string,
): Promise<void> {
  const documentsResponse = await request.get(`/api/v1/libraries/${libraryId}/documents?size=100`, {
    headers: { [DEV_USER_HEADER]: 'dev-admin' },
  })
  if (documentsResponse.ok()) {
    const body = (await documentsResponse.json()) as { items: Array<{ id: string }> }
    for (const document of body.items) {
      await request.delete(`/api/v1/libraries/${libraryId}/documents/${document.id}`, {
        headers: { [DEV_USER_HEADER]: 'dev-admin' },
      })
    }
  }
  const libraryResponse = await request.delete(`/api/v1/libraries/${libraryId}`, {
    headers: { [DEV_USER_HEADER]: 'dev-admin' },
  })
  expect(libraryResponse.ok()).toBe(true)
}

/**
 * Registers a test.describe-scoped cleanup: tests push the id of every library they create onto
 * the returned array, and test.afterAll deletes all of them once the block has finished -
 * regardless of whether any test failed, so a failed assertion never leaves a library behind for
 * the next local run. Call at the top of a test.describe block, not inside a test.
 */
export function cleanupLibraries(): string[] {
  const createdLibraryIds: string[] = []
  test.afterAll(async ({ request }) => {
    for (const libraryId of createdLibraryIds) {
      await deleteLibraryCompletely(request, libraryId)
    }
  })
  return createdLibraryIds
}
