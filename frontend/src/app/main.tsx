import '@fontsource/quicksand/400.css'
import '@fontsource/quicksand/500.css'
import '@fontsource/quicksand/600.css'
import '@fontsource/quicksand/700.css'
import '@fontsource/inter/400.css'
import '@fontsource/inter/500.css'
import '@fontsource/inter/600.css'
import '@fontsource/inter/700.css'
import '@fontsource/jetbrains-mono/400.css'
import '@fontsource/jetbrains-mono/500.css'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App.tsx'
import { installPreloadErrorReload } from './preloadErrorReload'

installPreloadErrorReload()

/** Starts the mocks if enabled; resolves to whether this document renders the app. */
async function enableMocking(): Promise<boolean> {
  if (!import.meta.env.VITE_ENABLE_MOCKS) {
    return true
  }
  if (import.meta.env.VITE_UI_LAB_PROTOTYPE) {
    const { startPrototype } = await import('./mocks/prototype/startPrototype')
    return startPrototype()
  }
  const { startMockWorker } = await import('./mocks/browser')
  await startMockWorker()
  return true
}

enableMocking()
  .then((renderApp) => {
    if (!renderApp) return
    createRoot(document.getElementById('root')!).render(
      <StrictMode>
        <App />
      </StrictMode>,
    )
  })
  .catch((err) => console.error('Failed to start app:', err))
