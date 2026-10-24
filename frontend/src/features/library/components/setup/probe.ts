import type { SourceBrowseResponse, SourceConnectionTestRequest } from '@/shared/api/types'
import { browseSource } from '../../api/libraryApi'
import type { LibrarySourceConfigPayload } from '../../utils/librarySourceConfig'
import { connectionFields } from '../sources/sourceConnection'
import type { SourceFormContext } from '../sources/types'

/** The connection test of a form's payload, scoped and framed by its context. */
export function testRequestOf(
  context: SourceFormContext,
  payload: LibrarySourceConfigPayload,
): SourceConnectionTestRequest {
  return connectionFields(context).probeRequest({
    sourceType: context.sourceType,
    ...payload,
  }) as SourceConnectionTestRequest
}

/** A listing of the form's source, scoped and framed by its context. */
export function browseWith(
  context: SourceFormContext,
  request: { sourceUrl: string; sourceCredentials?: string; query?: Record<string, unknown> },
): Promise<SourceBrowseResponse> {
  return browseSource(context.sourceType, connectionFields(context).probeRequest(request))
}

/** The note of an incomplete listing, null for a complete one. */
export function listingNote(response: SourceBrowseResponse): string | null {
  return response.complete ? null : (response.message ?? 'Die Liste ist unvollständig.')
}

/** The values the connection depends on, as a stable token. */
export function tokenOf(...parts: unknown[]): string {
  return JSON.stringify(parts)
}
