import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { SELF_DISCLOSURE_DOWNLOAD_URL } from '../api/selfDisclosureApi'
import { submitSelfDisclosureDownload } from './selfDisclosureDownload'

interface Submitted {
  method: string
  action: string
  target: string
  ticket: string | null
}

let submitted: Submitted[] = []

function frame(): HTMLIFrameElement {
  const found = document.querySelector<HTMLIFrameElement>('iframe')
  if (!found) throw new Error('no frame')
  return found
}

/** Lets the frame show a page at `href` with `body`, as after a refused submission. */
function frameShows(href: string, body: string | (() => never)) {
  const target = frame()
  Object.defineProperty(target, 'contentWindow', {
    configurable: true,
    value: { location: { href } },
  })
  Object.defineProperty(target, 'contentDocument', {
    configurable: true,
    get: () => (typeof body === 'function' ? body() : { body: { textContent: body } }),
  })
  target.dispatchEvent(new Event('load'))
}

describe('submitSelfDisclosureDownload', () => {
  beforeEach(() => {
    submitted = []
    // jsdom does not navigate; record what the browser would send instead
    vi.spyOn(HTMLFormElement.prototype, 'submit').mockImplementation(function (
      this: HTMLFormElement,
    ) {
      submitted.push({
        method: this.method,
        action: this.getAttribute('action') ?? '',
        target: this.target,
        ticket: new FormData(this).get('ticket') as string | null,
      })
    })
  })

  afterEach(() => {
    vi.restoreAllMocks()
    document.body.innerHTML = ''
  })

  it('posts the ticket in the body into a hidden frame and leaves only the frame behind', () => {
    submitSelfDisclosureDownload('ticket-1', vi.fn())

    expect(submitted).toEqual([
      {
        method: 'post',
        action: SELF_DISCLOSURE_DOWNLOAD_URL,
        target: frame().name,
        ticket: 'ticket-1',
      },
    ])
    expect(document.querySelector('form')).toBeNull()
    expect(frame().getAttribute('aria-hidden')).toBe('true')
    expect(frame().tabIndex).toBe(-1)
  })

  it('reuses the one frame, so a running download is never torn out of the page', () => {
    submitSelfDisclosureDownload('ticket-1', vi.fn())
    submitSelfDisclosureDownload('ticket-2', vi.fn())

    expect(document.querySelectorAll('iframe')).toHaveLength(1)
  })

  it('reports a refusal with status and code read from the frame', () => {
    const onRefused = vi.fn()
    submitSelfDisclosureDownload('ticket-1', onRefused)

    frameShows(
      `http://localhost${SELF_DISCLOSURE_DOWNLOAD_URL}`,
      JSON.stringify({ error: 'x', status: 404, code: 'SELF_DISCLOSURE_TICKET_INVALID' }),
    )

    expect(onRefused).toHaveBeenCalledWith({ status: 404, code: 'SELF_DISCLOSURE_TICKET_INVALID' })
  })

  it('ignores the empty page a new frame loads first', () => {
    const onRefused = vi.fn()
    submitSelfDisclosureDownload('ticket-1', onRefused)

    frameShows('about:blank', '')

    expect(onRefused).not.toHaveBeenCalled()
  })

  it('still reports a refusal it cannot read', () => {
    const onRefused = vi.fn()
    submitSelfDisclosureDownload('ticket-1', onRefused)

    frameShows(`http://localhost${SELF_DISCLOSURE_DOWNLOAD_URL}`, () => {
      throw new DOMException('blocked', 'SecurityError')
    })
    frameShows(`http://localhost${SELF_DISCLOSURE_DOWNLOAD_URL}`, '<html>Bad Gateway</html>')

    expect(onRefused).toHaveBeenNthCalledWith(1, { status: null, code: null })
    expect(onRefused).toHaveBeenNthCalledWith(2, { status: null, code: null })
  })

  it('stops listening once the caller is done', () => {
    const onRefused = vi.fn()
    const stop = submitSelfDisclosureDownload('ticket-1', onRefused)

    stop()
    frameShows(`http://localhost${SELF_DISCLOSURE_DOWNLOAD_URL}`, '{"status":503}')

    expect(onRefused).not.toHaveBeenCalled()
  })
})
