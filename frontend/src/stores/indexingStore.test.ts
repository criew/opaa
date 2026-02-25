import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest'
import { useIndexingStore } from './indexingStore'

describe('indexingStore', () => {
  beforeEach(() => {
    useIndexingStore.setState({
      status: 'IDLE',
      documentCount: 0,
      message: null,
      timestamp: null,
      isPolling: false,
      drawerOpen: false,
      snackbar: { open: false, message: '', severity: 'success' },
    })
  })

  afterEach(() => {
    useIndexingStore.getState().stopPolling()
  })

  it('starts with idle state', () => {
    const state = useIndexingStore.getState()
    expect(state.status).toBe('IDLE')
    expect(state.isPolling).toBe(false)
    expect(state.drawerOpen).toBe(false)
  })

  it('toggles drawer', () => {
    useIndexingStore.getState().toggleDrawer()
    expect(useIndexingStore.getState().drawerOpen).toBe(true)

    useIndexingStore.getState().toggleDrawer()
    expect(useIndexingStore.getState().drawerOpen).toBe(false)
  })

  it('sets drawer open state', () => {
    useIndexingStore.getState().setDrawerOpen(true)
    expect(useIndexingStore.getState().drawerOpen).toBe(true)

    useIndexingStore.getState().setDrawerOpen(false)
    expect(useIndexingStore.getState().drawerOpen).toBe(false)
  })

  it('closes snackbar', () => {
    useIndexingStore.setState({
      snackbar: { open: true, message: 'Test', severity: 'success' },
    })
    useIndexingStore.getState().closeSnackbar()
    expect(useIndexingStore.getState().snackbar.open).toBe(false)
  })

  it('triggers indexing and starts polling', async () => {
    vi.useFakeTimers()

    await useIndexingStore.getState().triggerIndexing()

    const state = useIndexingStore.getState()
    expect(state.status).toBe('RUNNING')
    expect(state.isPolling).toBe(true)

    useIndexingStore.getState().stopPolling()
    vi.useRealTimers()
  })

  it('stops polling', async () => {
    vi.useFakeTimers()

    await useIndexingStore.getState().triggerIndexing()
    expect(useIndexingStore.getState().isPolling).toBe(true)

    useIndexingStore.getState().stopPolling()
    expect(useIndexingStore.getState().isPolling).toBe(false)

    vi.useRealTimers()
  })
})
