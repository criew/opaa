import { http, HttpResponse } from 'msw'
import type {
  ConnectedAccount,
  ConnectedAccountConnectRequest,
  ConnectedAccountsOverview,
} from '../types/api'

const ME = '/api/v1/me/connected-accounts'

/** The secret the mock provider rejects, to try the refused sign-in in dev mode. */
export const MOCK_REJECTED_SECRET = 'falsch'

function initialOverview(): ConnectedAccountsOverview {
  return {
    accounts: [
      {
        profileId: 'connection-profile-nextcloud-person',
        profileName: 'Zugang Nextcloud intern',
        authMethod: 'PERSONAL_SECRET',
        secretForm: 'USERNAME_AND_PASSWORD',
        state: 'CONNECTED',
        accountLabel: 'avogt',
        released: true,
        reconnectable: true,
        notice: null,
        responsible: null,
        connectedAt: '2026-09-20T08:00:00Z',
        reconnectedAt: null,
        usedBy: [{ id: 'library-private-ablage', name: 'Meine Ablage' }],
      },
      {
        profileId: 'connection-profile-nextcloud-partner',
        profileName: 'Zugang Nextcloud Partner',
        authMethod: 'PERSONAL_SECRET',
        secretForm: 'TOKEN',
        state: 'EXPIRED',
        accountLabel: null,
        released: false,
        reconnectable: true,
        notice:
          'Nicht mehr freigegeben – die Verbindung läuft weiter und lässt sich trennen und neu verbinden; ein neues Konto ist nicht mehr möglich.',
        responsible: 'Systemverwaltung',
        connectedAt: '2026-08-01T08:00:00Z',
        reconnectedAt: '2026-09-01T08:00:00Z',
        usedBy: [],
      },
    ],
    connectable: [
      {
        profileId: 'connection-profile-opendesk',
        name: 'Zugang openDesk',
        authMethod: 'PERSONAL_SECRET',
        secretForm: 'TOKEN',
      },
    ],
    missingAccess: {
      responsible: 'Systemverwaltung',
      text: 'Zugänge für verbundene Konten legt die Systemverwaltung an und gibt sie frei. Fehlt Ihnen ein Zugang, wenden Sie sich an die Systemverwaltung.',
    },
  }
}

let overview: ConnectedAccountsOverview = initialOverview()

export function resetConnectedAccountMockState() {
  overview = initialOverview()
}

function error(status: number, message: string, code?: string) {
  return HttpResponse.json(
    { error: message, status, timestamp: new Date().toISOString(), code },
    { status },
  )
}

export const connectedAccountHandlers = [
  http.get(ME, () => HttpResponse.json(overview)),

  http.put(`${ME}/:profileId`, async ({ params, request }) => {
    const profileId = String(params.profileId)
    const body = (await request.json()) as ConnectedAccountConnectRequest
    const existing = overview.accounts.find((account) => account.profileId === profileId)
    const connectable = overview.connectable.find((profile) => profile.profileId === profileId)
    if (!existing && !connectable) return error(404, 'Zugang nicht gefunden')
    if (existing && !existing.reconnectable) {
      return error(403, 'Der Zugang ist gesperrt.', 'CONNECTOR_LOCKED')
    }
    if (body.secret === MOCK_REJECTED_SECRET) {
      return error(400, 'Die Anmeldung beim Zugang ist fehlgeschlagen.')
    }
    const now = new Date().toISOString()
    const account: ConnectedAccount = existing
      ? {
          ...existing,
          state: 'CONNECTED',
          accountLabel: body.username ?? existing.accountLabel ?? null,
          notice: existing.released ? null : existing.notice,
          reconnectedAt: now,
        }
      : {
          profileId,
          profileName: connectable!.name,
          authMethod: connectable!.authMethod,
          secretForm: connectable!.secretForm,
          state: 'CONNECTED',
          accountLabel: body.username ?? null,
          released: true,
          reconnectable: true,
          notice: null,
          responsible: null,
          connectedAt: now,
          reconnectedAt: null,
          usedBy: [],
        }
    overview = {
      ...overview,
      accounts: existing
        ? overview.accounts.map((item) => (item.profileId === profileId ? account : item))
        : [...overview.accounts, account],
      connectable: overview.connectable.filter((profile) => profile.profileId !== profileId),
    }
    return HttpResponse.json(account)
  }),

  http.delete(`${ME}/:profileId`, ({ params }) => {
    const profileId = String(params.profileId)
    const existing = overview.accounts.find((account) => account.profileId === profileId)
    if (!existing) return error(404, 'Keine Verbindung zu diesem Zugang')
    overview = {
      ...overview,
      accounts:
        existing.usedBy.length > 0
          ? overview.accounts.map((item): ConnectedAccount =>
              item.profileId === profileId
                ? {
                    ...item,
                    state: 'DISCONNECTED',
                    notice:
                      'Getrennt – Ihre private Bibliothek ruht, bis Sie das Konto neu verbinden.',
                  }
                : item,
            )
          : overview.accounts.filter((item) => item.profileId !== profileId),
    }
    return new HttpResponse(null, { status: 204 })
  }),
]
