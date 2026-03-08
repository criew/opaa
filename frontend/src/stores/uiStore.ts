import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export type ThemeMode = 'dark' | 'light' | 'system'

interface UiState {
  sidebarOpen: boolean
  setSidebarOpen: (open: boolean) => void
  toggleSidebar: () => void
  themeMode: ThemeMode
  setThemeMode: (mode: ThemeMode) => void
}

export const useUiStore = create<UiState>()(
  persist(
    (set, get) => ({
      sidebarOpen: false,
      setSidebarOpen: (open: boolean) => set({ sidebarOpen: open }),
      toggleSidebar: () => set({ sidebarOpen: !get().sidebarOpen }),
      themeMode: 'system',
      setThemeMode: (mode: ThemeMode) => set({ themeMode: mode }),
    }),
    {
      name: 'opaa-ui-preferences',
      partialize: (state) => ({ themeMode: state.themeMode }),
    },
  ),
)
