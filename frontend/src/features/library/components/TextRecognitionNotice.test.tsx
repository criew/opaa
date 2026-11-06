import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { TextRecognitionBlock } from '@/shared/api/types'
import { server } from '@/test/server'
import { renderWithProviders } from '@/test/test-utils'
import TextRecognitionNotice from './TextRecognitionNotice'
import { mockTextRecognitionBlock } from '../mocks/indexingFixtures'

const LIBRARY = 'lib-ocr'
const FILE = { name: 'Haushaltsplan-1987.pdf', selector: 'p' } as const

function answer(pending: number | null, items: TextRecognitionBlock[]) {
  server.use(
    http.get('/api/v1/libraries/:libraryId/text-recognition/blocks', () =>
      HttpResponse.json({ items, pending }),
    ),
  )
}

describe('TextRecognitionNotice', () => {
  it('stays away while nothing waits and nothing is locked', async () => {
    answer(null, [])

    renderWithProviders(<TextRecognitionNotice libraryId={LIBRARY} canRelease refreshToken={0} />)

    await new Promise((resolve) => setTimeout(resolve, 50))
    expect(screen.queryByTestId('text-recognition-notice')).not.toBeInTheDocument()
  })

  it('names the scans waiting for text recognition', async () => {
    answer(3, [])

    renderWithProviders(<TextRecognitionNotice libraryId={LIBRARY} canRelease refreshToken={0} />)

    expect(await screen.findByText('3 Scans warten auf die Texterkennung')).toBeInTheDocument()
    expect(screen.queryByText(/gesperrt/)).not.toBeInTheDocument()
  })

  it('lists a locked scan with its reason and releases it for a new attempt', async () => {
    answer(0, [mockTextRecognitionBlock])
    let released: string | null = null
    server.use(
      http.delete(
        '/api/v1/libraries/:libraryId/text-recognition/blocks/:documentId',
        ({ params }) => {
          released = params.documentId as string
          answer(1, [])
          return new HttpResponse(null, { status: 204 })
        },
      ),
    )
    const user = userEvent.setup()

    renderWithProviders(<TextRecognitionNotice libraryId={LIBRARY} canRelease refreshToken={0} />)

    expect(await screen.findByText('Texterkennung für 1 Scan gesperrt')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /Anzeigen/ }))
    expect(screen.getByText(FILE.name, { selector: FILE.selector })).toBeInTheDocument()
    expect(
      screen.getByText(
        /Seitengrenze überschritten \(240 Seiten, erlaubt sind 100\) · docling-serve/,
      ),
    ).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Freigeben/ }))

    await waitFor(() => expect(released).toBe(mockTextRecognitionBlock.documentId))
    expect(await screen.findByText('1 Scan wartet auf die Texterkennung')).toBeInTheDocument()
    expect(screen.queryByText(FILE.name, { selector: FILE.selector })).not.toBeInTheDocument()
  })

  it('offers no release to a reader', async () => {
    answer(0, [mockTextRecognitionBlock])
    const user = userEvent.setup()

    renderWithProviders(
      <TextRecognitionNotice libraryId={LIBRARY} canRelease={false} refreshToken={0} />,
    )

    await user.click(await screen.findByRole('button', { name: /Anzeigen/ }))
    expect(screen.getByText(FILE.name, { selector: FILE.selector })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Freigeben/ })).not.toBeInTheDocument()
  })
})
