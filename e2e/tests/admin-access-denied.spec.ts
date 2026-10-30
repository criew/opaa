import { expect, test } from '../fixtures/auth'
import { apiAs } from '../fixtures/externalAccess'

/**
 * A person without a system role reaches no administration or revision page and none of their
 * read endpoints - the interface refusal and the server refusal, through nginx, for every area.
 * The group page is left out: group managers without a system role work there.
 */

const PAGES: Array<{ path: string; refusal: RegExp }> = [
  { path: '/admin/users/accounts', refusal: /nicht freigegeben/ },
  { path: '/admin/branding', refusal: /nicht freigegeben/ },
  { path: '/admin/settings-transfer', refusal: /nicht freigegeben/ },
  { path: '/admin/models/chat', refusal: /nicht freigegeben/ },
  { path: '/admin/identity-providers', refusal: /nicht freigegeben/ },
  { path: '/admin/directory-sync', refusal: /nicht freigegeben/ },
  { path: '/admin/connections/settings', refusal: /nicht freigegeben/ },
  { path: '/admin/capabilities', refusal: /nicht freigegeben/ },
  { path: '/admin/succession/open', refusal: /nicht freigegeben/ },
  { path: '/admin/mail/server', refusal: /nicht freigegeben/ },
  { path: '/admin/search/overview', refusal: /nicht freigegeben/ },
  { path: '/admin/privacy', refusal: /nicht freigegeben/ },
  { path: '/revision/rechtehistorie', refusal: /der Revision vorbehalten/ },
  { path: '/revision/verbindungsprotokoll', refusal: /der Revision vorbehalten/ },
  { path: '/revision/rollenantraege', refusal: /entscheidet die Revision/ },
]

// Required query parameters are filled in, so a refusal is the role check, not the validation.
const READ_ENDPOINTS = [
  '/api/v1/admin/accounts',
  '/api/v1/admin/capabilities',
  '/api/v1/admin/compliance/data-collection-disclosure',
  '/api/v1/admin/connection-log/retention',
  '/api/v1/admin/connection-profiles',
  '/api/v1/admin/diagnostic-context/retention',
  '/api/v1/admin/diagnostic-impersonation-grants',
  '/api/v1/admin/directory-sync/status',
  '/api/v1/admin/external-access/tokens',
  '/api/v1/admin/indexing/pipeline-versions',
  '/api/v1/admin/local-auth-settings',
  '/api/v1/admin/local-users',
  '/api/v1/admin/models',
  '/api/v1/admin/oidc-providers',
  '/api/v1/admin/outbound-proxy',
  '/api/v1/admin/permission-history/retention',
  '/api/v1/admin/private-libraries/summary',
  '/api/v1/admin/retention/chat-auto-cleanup',
  '/api/v1/admin/search/status',
  '/api/v1/admin/self-disclosure/settings',
  '/api/v1/admin/succession?kind=OPEN_SUCCESSION',
  '/api/v1/admin/users',
  '/api/v1/audit/auditor-role-requests',
  '/api/v1/audit/connection-log?from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z&reason=E2E-Pruefung',
  '/api/v1/audit/diagnostic-context-events?from=2026-01-01T00:00:00Z&to=2026-01-02T00:00:00Z&reason=E2E-Pruefung',
  '/api/v1/system/settings/export',
]

test.describe('Verwaltung und Revision ohne Systemrolle', () => {
  test('1. Jede Verwaltungs- und Revisionsseite verweigert sich', async ({
    regularUserPage: page,
  }) => {
    // A positive anchor first: the global bar is rendered, and it offers no way into the area.
    await expect(page.getByRole('link', { name: 'Katalog' })).toBeVisible()
    await expect(page.getByRole('link', { name: 'Admin' })).toHaveCount(0)

    for (const { path, refusal } of PAGES) {
      await page.goto(path)
      await expect(page.getByText(refusal).first(), path).toBeVisible()
      await expect(page.getByRole('navigation', { name: 'Administration' }), path).toHaveCount(0)
    }
  })

  test('2. Die Lesewege der Verwaltung und Revision antworten mit 403', async () => {
    const api = await apiAs('dev-user')
    try {
      const statuses = await Promise.all(
        READ_ENDPOINTS.map(async (endpoint) => `${endpoint} ${(await api.get(endpoint)).status()}`),
      )
      expect(statuses).toEqual(READ_ENDPOINTS.map((endpoint) => `${endpoint} 403`))
    } finally {
      await api.dispose()
    }
  })
})
