import { expect, test } from '@playwright/test'

// Nachtrag zu #1721: Der nginx des Frontend-Containers reichte nur /api/ an das Backend durch,
// /mcp fiel in den SPA-Fallback und beantwortete jeden MCP-Aufruf mit 200 und index.html. Der Test
// läuft gegen den echten nginx des Compose-Stacks und prüft die Invariante, dass /mcp am Backend
// endet: Die Antwort kommt aus dem Fremdzugangs-Kanal (401/404/503, JSON), nie aus der Auslieferung
// der Anwendung. Bewusst ohne Token und unabhängig davon, ob der Kanal gerade geschaltet ist -
// welche der drei Absagen kommt, entscheidet der Schalter, die Herkunft der Antwort nicht.
test.describe('MCP-Endpunkt hinter dem Reverse-Proxy (#1721)', () => {
  test('POST /mcp wird an das Backend durchgereicht, nicht von der SPA beantwortet', async ({
    request,
  }) => {
    const response = await request.post('/mcp', {
      headers: { 'Content-Type': 'application/json' },
      data: { jsonrpc: '2.0', id: 1, method: 'tools/list' },
    })

    expect([401, 404, 503]).toContain(response.status())
    expect(response.headers()['content-type']).toContain('application/json')
    const body = await response.text()
    expect(body.toLowerCase()).not.toContain('<!doctype html')
  })
})
