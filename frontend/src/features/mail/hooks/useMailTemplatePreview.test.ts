import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import type { MailTemplatePreviewResponse } from '@/shared/api/types'
import { MAIL_PREVIEW_DEBOUNCE_MS, useMailTemplatePreview } from './useMailTemplatePreview'

function previewOf(subject: string): MailTemplatePreviewResponse {
  return { subject, bodyPlain: subject, bodyHtml: `<p>${subject}</p>`, variables: {} }
}

/**
 * The two guarantees of the preview: a sequence of inputs is one request, and an
 * outdated response does not write the preview back.
 *
 * The responses are deliberately released by hand here instead of via delays - the
 * order „second response before the first" is thus a guarantee of the test and not the
 * result of two competing timers.
 */
describe('useMailTemplatePreview', () => {
  let requested: string[] = []
  let release: Array<(value: MailTemplatePreviewResponse) => void> = []

  beforeEach(() => {
    vi.useFakeTimers()
    requested = []
    release = []
    server.use(
      http.post('/api/v1/system/mail-templates/:templateKey/preview', async ({ request }) => {
        const body = (await request.json()) as { subject?: string }
        requested.push(body.subject ?? '')
        const answer = await new Promise<MailTemplatePreviewResponse>((resolve) => {
          release.push(resolve)
        })
        return HttpResponse.json(answer)
      }),
    )
  })

  afterEach(() => {
    release.forEach((resolve) => resolve(previewOf('aufgeräumt')))
    vi.useRealTimers()
  })

  it('bündelt eine Folge von Eingaben zu einer einzigen Anfrage', async () => {
    const { rerender } = renderHook(
      ({ subject }) => useMailTemplatePreview('TEST_MAIL', subject, 'Text'),
      { initialProps: { subject: 'A' } },
    )

    rerender({ subject: 'AB' })
    rerender({ subject: 'ABC' })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(MAIL_PREVIEW_DEBOUNCE_MS)
    })

    expect(requested).toEqual(['ABC'])
  })

  it('verwirft eine Antwort, die von einer neueren überholt wurde', async () => {
    const { rerender, result } = renderHook(
      ({ subject }) => useMailTemplatePreview('TEST_MAIL', subject, 'Text'),
      { initialProps: { subject: 'alt' } },
    )

    await act(async () => {
      await vi.advanceTimersByTimeAsync(MAIL_PREVIEW_DEBOUNCE_MS)
    })
    rerender({ subject: 'neu' })
    await act(async () => {
      await vi.advanceTimersByTimeAsync(MAIL_PREVIEW_DEBOUNCE_MS)
    })
    expect(requested).toEqual(['alt', 'neu'])

    // the newer answer arrives first ...
    await act(async () => {
      release[1](previewOf('neu'))
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(result.current.preview?.subject).toBe('neu')

    // ... and the overtaken one no longer writes back
    await act(async () => {
      release[0](previewOf('alt'))
      await vi.advanceTimersByTimeAsync(0)
    })
    expect(result.current.preview?.subject).toBe('neu')
  })
})
