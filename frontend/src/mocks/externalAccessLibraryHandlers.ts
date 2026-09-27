import { http, HttpResponse } from 'msw'
import { mockLibraryDetails } from './libraryFixtures'

export const externalAccessLibraryHandlers = [
  // spiegelt LibraryExternalAccessService#setExternalAccess: Setzen verlangt ein Ablaufdatum
  // innerhalb eines Jahres, Zurücknehmen darf keines mitbringen (#1731).
  http.put('/api/v1/libraries/:libraryId/external-access', async ({ params, request }) => {
    const libraryId = String(params.libraryId)
    const library = mockLibraryDetails[libraryId]
    if (!library?.externalAccess) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as { enabled: boolean; expiresAt?: string | null }
    if (body.enabled && !body.expiresAt) {
      return HttpResponse.json(
        { error: 'Eine Freigabe für Fremdzugänge braucht ein Ablaufdatum' },
        { status: 400 },
      )
    }
    const maxDays = library.externalAccess.maxReleaseDays
    if (body.enabled && body.expiresAt) {
      const days = (new Date(body.expiresAt).getTime() - Date.now()) / 86_400_000
      if (days > maxDays) {
        return HttpResponse.json(
          { error: `Eine Freigabe für Fremdzugänge gilt höchstens ${maxDays} Tage` },
          { status: 400 },
        )
      }
    }
    library.externalAccess = {
      ...library.externalAccess,
      state: body.enabled ? 'ACTIVE' : 'WITHDRAWN',
      expiresAt: body.enabled ? (body.expiresAt ?? null) : library.externalAccess.expiresAt,
      setAt: new Date().toISOString(),
      setByDisplayName: 'Mock Benutzer',
    }
    return HttpResponse.json(library.externalAccess)
  }),

  http.get('/api/v1/admin/external-access/libraries', () => {
    const released = Object.values(mockLibraryDetails)
      .filter((library) => library.externalAccess?.state === 'ACTIVE')
      .map((library) => ({
        libraryId: library.id,
        libraryName: library.name,
        externalAccess: library.externalAccess,
      }))
    return HttpResponse.json(released)
  }),
]
