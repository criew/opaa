import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export type ThemeMode = 'dark' | 'light' | 'system'

interface UiState {
  sidebarOpen: boolean
  setSidebarOpen: (open: boolean) => void
  toggleSidebar: () => void
  /**
   * The user's own choice, or `null` while they have not made one (#583). The distinction is
   * load-bearing: `null` is what lets the operator's configured default colour scheme apply, and
   * a plain `'system'` default here would have made "never decided" indistinguishable from
   * "deliberately picked system" - silently overriding every operator default with system.
   *
   * Read through `resolveThemeMode` (src/theme/colorScheme.ts) rather than directly, so the
   * precedence between user choice and operator default lives in exactly one place.
   */
  themeMode: ThemeMode | null
  setThemeMode: (mode: ThemeMode) => void
  /** Back to the operator's configured default - the only way out of an own choice. */
  clearThemeMode: () => void
}

export const useUiStore = create<UiState>()(
  persist(
    (set, get) => ({
      sidebarOpen: false,
      setSidebarOpen: (open: boolean) => set({ sidebarOpen: open }),
      toggleSidebar: () => set({ sidebarOpen: !get().sidebarOpen }),
      themeMode: null,
      setThemeMode: (mode: ThemeMode) => set({ themeMode: mode }),
      clearThemeMode: () => set({ themeMode: null }),
    }),
    {
      name: 'opaa-ui-preferences',
      partialize: (state) => ({ themeMode: state.themeMode }),
    },
  ),
)
