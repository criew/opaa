import { createRoot } from 'react-dom/client'
import { handlers } from '../handlers'
import { installInPageMocks } from '../inPageMocks'
import PrototypeBar from './PrototypeBar'
import { FRAME_PARAM, NARROW_WIDTH, WIDTH_PARAM } from './prototypeConfig'

/**
 * Starts the static UI-lab prototype: mocks answered in the page, the prototype bar in its own
 * React root. At normal width the app renders in this document; at the simulated narrow width it
 * renders in a same-origin iframe of that width, so the app's media queries see a narrow viewport,
 * and the frame's route is mirrored into this document's hash for deep links and reloads.
 *
 * @returns whether the caller should render the app into `#root` of this document
 */
export function startPrototype(): boolean {
  installInPageMocks(handlers)
  const params = new URLSearchParams(window.location.search)
  if (params.has(FRAME_PARAM)) return true

  const narrow = params.get(WIDTH_PARAM) === `${NARROW_WIDTH}`
  const frame = narrow ? mountNarrowFrame(params) : null
  const navigate = (path: string) => {
    const target = frame?.contentWindow ?? window
    target.location.hash = path
  }

  const host = document.createElement('div')
  document.body.appendChild(host)
  createRoot(host).render(<PrototypeBar navigate={navigate} />)
  return !narrow
}

function mountNarrowFrame(params: URLSearchParams): HTMLIFrameElement {
  const frameParams = new URLSearchParams(params)
  frameParams.delete(WIDTH_PARAM)
  frameParams.set(FRAME_PARAM, '1')
  const frame = document.createElement('iframe')
  frame.title = `OPAA bei ${NARROW_WIDTH} px Breite`
  frame.src = `${window.location.pathname}?${frameParams}${window.location.hash}`
  Object.assign(frame.style, {
    display: 'block',
    width: `${NARROW_WIDTH}px`,
    height: '100vh',
    margin: '0 auto',
    border: 'none',
    boxShadow: '0 0 0 1px #3b1f5c, 0 0 24px rgba(0,0,0,.25)',
  })
  document.body.style.margin = '0'
  document.body.style.background = '#e9e4f0'
  document.getElementById('root')?.replaceChildren(frame)
  // The router navigates with pushState, which fires no event the parent could listen to.
  window.setInterval(() => {
    const hash = frame.contentWindow?.location.hash
    if (hash && hash !== window.location.hash) window.history.replaceState(null, '', hash)
  }, 500)
  return frame
}
