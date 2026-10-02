import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { useState } from 'react'
import { renderWithProviders } from '../../test/test-utils'
import { server } from '../../mocks/server'
import type { CatalogEntryResponse } from '../../types/api'
import AssetTilePicker from './AssetTilePicker'
import type { AssetPick } from './assetPick'

function entry(assetId: string, name: string): CatalogEntryResponse {
  return {
    assetType: 'KNOWLEDGE_LIBRARY',
    assetId,
    name,
    description: null,
    ownerType: 'USER',
    ownerLabel: null,
    origin: 'LOCAL',
    itemCount: 0,
    spaceCount: 0,
    succession: null,
  } as unknown as CatalogEntryResponse
}

function Harness() {
  const [value, setValue] = useState<AssetPick[]>([])
  return <AssetTilePicker value={value} onChange={setValue} aria-label="Inhalte" />
}

describe('AssetTilePicker', () => {
  it('keeps the tiles already shown when a further page fails, and offers to try again', async () => {
    let secondPageCalls = 0
    server.use(
      http.get('/api/v1/catalog', ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page') ?? '0')
        if (page === 0) {
          return HttpResponse.json({
            entries: [entry('erste', 'Erste Bibliothek')],
            page: 0,
            size: 50,
            totalElements: 2,
            totalPages: 2,
          })
        }
        secondPageCalls += 1
        if (secondPageCalls === 1) {
          return HttpResponse.json({ error: 'Dienst nicht erreichbar' }, { status: 503 })
        }
        return HttpResponse.json({
          entries: [entry('zweite', 'Zweite Bibliothek')],
          page: 1,
          size: 50,
          totalElements: 2,
          totalPages: 2,
        })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<Harness />)

    await user.click(await screen.findByRole('button', { name: 'Weitere laden' }))

    expect(await screen.findByText(/Weitere Einträge konnten nicht geladen werden/)).toBeVisible()
    expect(screen.getByRole('checkbox', { name: /^Erste Bibliothek/ })).toBeVisible()

    await user.click(screen.getByRole('button', { name: 'Erneut versuchen' }))

    expect(await screen.findByRole('checkbox', { name: /^Zweite Bibliothek/ })).toBeVisible()
    expect(screen.getByRole('checkbox', { name: /^Erste Bibliothek/ })).toBeVisible()
    expect(
      screen.queryByText(/Weitere Einträge konnten nicht geladen werden/),
    ).not.toBeInTheDocument()
  })
})
