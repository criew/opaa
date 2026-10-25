import { getResponse, isPassthroughResponse, type RequestHandler } from 'msw'

type Fetch = typeof window.fetch

/**
 * Answers the page's requests with the MSW handlers without a service worker: `window.fetch` and
 * `window.XMLHttpRequest` are replaced in this document. A request no handler answers (or one a
 * handler passes through) goes to the original `fetch`. For the static UI-lab prototype, which
 * runs in a sandboxed iframe where a service worker cannot be relied on.
 */
export function installInPageMocks(handlers: RequestHandler[]) {
  const originalFetch: Fetch = window.fetch.bind(window)

  async function respond(request: Request): Promise<Response> {
    const mocked = await getResponse(handlers, request.clone(), {
      baseUrl: window.location.origin,
    })
    if (mocked && !isPassthroughResponse(mocked)) return mocked
    return originalFetch(request)
  }

  replaceGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) =>
    respond(new Request(input instanceof Request ? input : absolute(input), init)),
  )
  replaceGlobal('XMLHttpRequest', createMockXhr(respond))
}

function absolute(url: string | URL) {
  return new URL(url, document.baseURI).href
}

/** Redefines rather than assigns: another interceptor may have made the property read-only. */
function replaceGlobal(name: 'fetch' | 'XMLHttpRequest', value: unknown) {
  Object.defineProperty(window, name, { value, configurable: true, writable: true })
}

const NO_BODY_METHODS = new Set(['GET', 'HEAD'])

/**
 * The part of XMLHttpRequest that axios and the browser's own callers use, backed by `respond`:
 * the whole response is read before `load`, so download progress arrives as one final event and
 * upload progress not at all.
 */
function createMockXhr(respond: (request: Request) => Promise<Response>) {
  return class MockXMLHttpRequest extends EventTarget {
    static readonly UNSENT = 0
    static readonly OPENED = 1
    static readonly HEADERS_RECEIVED = 2
    static readonly LOADING = 3
    static readonly DONE = 4

    readyState = 0
    status = 0
    statusText = ''
    response: unknown = null
    responseText = ''
    responseURL = ''
    responseType: XMLHttpRequestResponseType = ''
    timeout = 0
    withCredentials = false
    readonly upload = new EventTarget()

    onreadystatechange: ((event: Event) => void) | null = null
    onloadstart: ((event: ProgressEvent) => void) | null = null
    onprogress: ((event: ProgressEvent) => void) | null = null
    onload: ((event: ProgressEvent) => void) | null = null
    onloadend: ((event: ProgressEvent) => void) | null = null
    onerror: ((event: ProgressEvent) => void) | null = null
    onabort: ((event: ProgressEvent) => void) | null = null
    ontimeout: ((event: ProgressEvent) => void) | null = null

    private method = 'GET'
    private url = ''
    private readonly requestHeaders = new Headers()
    private responseHeaders = new Headers()
    private controller: AbortController | null = null
    private settled = false

    open(method: string, url: string | URL) {
      this.method = method.toUpperCase()
      this.url = absolute(url)
      this.setReadyState(1)
    }

    setRequestHeader(name: string, value: string) {
      this.requestHeaders.append(name, value)
    }

    overrideMimeType() {}

    getResponseHeader(name: string) {
      return this.responseHeaders.get(name)
    }

    getAllResponseHeaders() {
      let all = ''
      this.responseHeaders.forEach((value, name) => {
        all += `${name}: ${value}\r\n`
      })
      return all
    }

    send(body?: Document | XMLHttpRequestBodyInit | null) {
      const controller = new AbortController()
      this.controller = controller
      const request = new Request(this.url, {
        method: this.method,
        headers: this.requestHeaders,
        body: NO_BODY_METHODS.has(this.method) ? undefined : (body as BodyInit | null),
        credentials: this.withCredentials ? 'include' : 'same-origin',
        signal: controller.signal,
      })
      this.fire('loadstart')
      if (this.timeout > 0) {
        window.setTimeout(() => this.fail('timeout'), this.timeout)
      }
      respond(request)
        .then((response) => this.complete(response))
        .catch(() => this.fail('error'))
    }

    abort() {
      this.controller?.abort()
      this.fail('abort')
    }

    private async complete(response: Response) {
      if (this.settled) return
      this.status = response.status
      this.statusText = response.statusText
      this.responseURL = response.url || this.url
      this.responseHeaders = response.headers
      this.setReadyState(2)
      this.setReadyState(3)
      switch (this.responseType) {
        case 'blob':
          this.response = await response.blob()
          break
        case 'arraybuffer':
          this.response = await response.arrayBuffer()
          break
        case 'json': {
          const text = await response.text()
          this.response = text ? JSON.parse(text) : null
          break
        }
        default:
          this.responseText = await response.text()
          this.response = this.responseText
      }
      if (this.settled) return
      this.settled = true
      this.setReadyState(4)
      this.fire('progress')
      this.fire('load')
      this.fire('loadend')
    }

    private fail(type: 'error' | 'abort' | 'timeout') {
      if (this.settled) return
      this.settled = true
      if (type === 'timeout') this.controller?.abort()
      this.status = 0
      this.setReadyState(4)
      this.fire(type)
      this.fire('loadend')
    }

    private setReadyState(state: number) {
      this.readyState = state
      this.dispatch(new Event('readystatechange'), this.onreadystatechange)
    }

    private fire(type: string) {
      const handler = this[`on${type}` as 'onload']
      this.dispatch(new ProgressEvent(type), handler)
    }

    private dispatch<E extends Event>(event: E, handler: ((event: E) => void) | null) {
      handler?.call(this, event)
      this.dispatchEvent(event)
    }
  }
}
