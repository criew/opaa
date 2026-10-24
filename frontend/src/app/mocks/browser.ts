import { setupWorker } from 'msw/browser'
import { handlers } from './handlers'

export const worker = setupWorker(...handlers)

/**
 * Starts the worker and keeps this page registered with it. The browser may stop an idle service
 * worker at any time; the restarted one has forgotten its clients and lets every request through
 * to the network. Announcing the page again every second re-registers it after such a restart.
 */
export async function startMockWorker() {
  await worker.start({ onUnhandledFrame: 'bypass' })
  window.setInterval(() => {
    navigator.serviceWorker?.controller?.postMessage('MOCK_ACTIVATE')
  }, 1000)
}
