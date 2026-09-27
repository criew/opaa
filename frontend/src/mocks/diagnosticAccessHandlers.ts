import { http, HttpResponse } from 'msw'
import { mockLibraryDetails } from './libraryFixtures'

export const diagnosticAccessHandlers = [
  // mirrors LibraryDiagnosticsLockService#setLocked - only a real OWNER grant may
  // toggle the lock, the exact 403 message the backend sends when that is not the case.
  http.put('/api/v1/libraries/:libraryId/diagnostics-lock', async ({ params, request }) => {
    const libraryId = String(params.libraryId)
    const library = mockLibraryDetails[libraryId]
    if (!library) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    //  review: mirrors diagnosticsLockToggleable, not myRole - myRole alone would let a
    // mocked system-admin bypass (myRole 'OWNER' without an independent grant) through.
    if (!library.diagnosticsLockToggleable) {
      return HttpResponse.json(
        { error: 'Die Diagnosesperre setzt und löst nur die für die Bibliothek zuständige Stelle' },
        { status: 403 },
      )
    }
    const body = (await request.json()) as { locked: boolean }
    library.diagnosticsLocked = body.locked
    return HttpResponse.json({ libraryId, locked: body.locked })
  }),
]
