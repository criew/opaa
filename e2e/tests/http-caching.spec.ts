import { expect, test } from '../fixtures/auth'

// #812: index.html kam ohne Cache-Control - Browser cachten sie heuristisch und zeigten nach
// Deployments den alten Stand, bis hart neu geladen wurde. Läuft gegen den echten nginx des
// Compose-Stacks, wie die Header-Zusicherungen aus docs/deployment.md.
test.describe('Auslieferungs-Caching (#812)', () => {
  test('index.html verlangt Revalidierung, gehashte Assets cachen unbegrenzt', async ({
    authenticatedPage: page,
  }) => {
    const indexResponse = await page.request.get('/')
    expect(indexResponse.status()).toBe(200)
    expect(indexResponse.headers()['cache-control']).toBe('no-cache')
    // Der SPA-Fallback liefert dieselbe index.html - auch dort muss der Header stehen.
    const spaResponse = await page.request.get('/spaces')
    expect(spaResponse.headers()['cache-control']).toBe('no-cache')
    // Die Security-Header dürfen durch den neuen location-Block nicht verloren gehen
    // (nginx-add_header-Vererbungsregel, #409).
    expect(indexResponse.headers()['x-content-type-options']).toBe('nosniff')

    const html = await indexResponse.text()
    const asset = html.match(/assets\/[^"]+\.js/)?.[0]
    expect(asset).toBeTruthy()
    const assetResponse = await page.request.get(`/${asset}`)
    expect(assetResponse.status()).toBe(200)
    expect(assetResponse.headers()['cache-control']).toBe('public, max-age=31536000, immutable')
    expect(assetResponse.headers()['x-content-type-options']).toBe('nosniff')
  })
})
