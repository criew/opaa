import { create } from 'zustand'
import { getLinkTargets } from './linkTargetsApi'

interface LinkTargetsState {
  /** Origins besides the application's own to which a link may be clickable. */
  allowedOrigins: string[]
  status: 'idle' | 'loading' | 'loaded'
  /**
   * Loads the list once. A failed request leaves it empty, so only the own origin is clickable,
   * and the next renderer to mount asks again.
   */
  loadLinkTargets: () => Promise<void>
  /** Takes over the list an administrative change returned. */
  setAllowedOrigins: (origins: string[]) => void
}

export const useLinkTargetsStore = create<LinkTargetsState>((set, get) => ({
  allowedOrigins: [],
  status: 'idle',

  loadLinkTargets: async () => {
    if (get().status !== 'idle') return
    set({ status: 'loading' })
    try {
      const loaded = await getLinkTargets()
      // The same list keeps its reference, so a rendered answer is not rebuilt for nothing.
      const current = get().allowedOrigins
      const same =
        current.length === loaded.allowedOrigins.length &&
        current.every((origin, index) => origin === loaded.allowedOrigins[index])
      set({ allowedOrigins: same ? current : loaded.allowedOrigins, status: 'loaded' })
    } catch {
      set({ allowedOrigins: [], status: 'idle' })
    }
  },

  setAllowedOrigins: (origins) => set({ allowedOrigins: origins, status: 'loaded' }),
}))
