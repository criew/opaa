import { SELF_DISCLOSURE_DOWNLOAD_URL } from '../api/selfDisclosureApi'

/** What the download address answered instead of the ZIP, as far as the page can read it. */
export interface DownloadRefusal {
  status: number | null
  code: string | null
}

const FRAME_NAME = 'opaa-self-disclosure-download'

function downloadFrame(): HTMLIFrameElement {
  const existing = document.querySelector<HTMLIFrameElement>(`iframe[name="${FRAME_NAME}"]`)
  if (existing) return existing
  const frame = document.createElement('iframe')
  frame.name = FRAME_NAME
  frame.title = 'Download Ihrer Daten'
  frame.tabIndex = -1
  frame.setAttribute('aria-hidden', 'true')
  frame.style.display = 'none'
  document.body.appendChild(frame)
  return frame
}

function isBlank(frame: HTMLIFrameElement): boolean {
  try {
    return frame.contentWindow?.location.href === 'about:blank'
  } catch {
    return false
  }
}

function refusalOf(frame: HTMLIFrameElement): DownloadRefusal {
  try {
    const body = JSON.parse(frame.contentDocument?.body?.textContent ?? '') as unknown
    if (typeof body === 'object' && body !== null) {
      const { status, code } = body as { status?: unknown; code?: unknown }
      return {
        status: typeof status === 'number' ? status : null,
        code: typeof code === 'string' ? code : null,
      }
    }
  } catch {
    // not an ErrorResponse, or a page the browser does not let us read
  }
  return { status: null, code: null }
}

/**
 * Hands the ticket to the browser as a native form submission into a hidden frame, so the ZIP
 * streams straight to the download manager and a refusal does not replace the application. The
 * frame stays in the document: removing it could end a running download. A ZIP never loads into
 * the frame; a `load` therefore means a refusal, reported to `onRefused`. Whether a started
 * download completed or broke off the page learns only from the status. Returns a function that
 * stops listening.
 */
export function submitSelfDisclosureDownload(
  ticket: string,
  onRefused: (refusal: DownloadRefusal) => void,
): () => void {
  const frame = downloadFrame()
  const handleLoad = () => {
    // a frame that has just been created loads its empty page first
    if (isBlank(frame)) return
    onRefused(refusalOf(frame))
  }
  frame.addEventListener('load', handleLoad)

  const form = document.createElement('form')
  form.method = 'POST'
  form.action = SELF_DISCLOSURE_DOWNLOAD_URL
  form.target = FRAME_NAME
  form.style.display = 'none'
  const field = document.createElement('input')
  field.type = 'hidden'
  field.name = 'ticket'
  field.value = ticket
  form.appendChild(field)
  document.body.appendChild(form)
  form.submit()
  form.remove()

  return () => frame.removeEventListener('load', handleLoad)
}
