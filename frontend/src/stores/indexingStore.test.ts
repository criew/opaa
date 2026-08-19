import { describe, expect, it, beforeEach, afterEach, vi } from 'vitest'
import { useIndexingStore } from './indexingStore'

describe('indexingStore', () => {
  beforeEach(() => {
    useIndexingStore.setState({
      status: 'IDLE',
      documentCount: 0,
      totalDocuments: 0,
      documentsSkipped: 0,
      message: null,
      timestamp: null,
      isPolling: false,
      snackbar: { open: false, message: '', severity: 'success' },
    })
  })

  afterEach(() => {
    useIndexingStore.getState().stopPolling()
  })

  it('starts with idle state', () => {
    const state = useIndexingStore.getState()
    expect(state.status).toBe('IDLE')
    expect(state.totalDocuments).toBe(0)
    expect(state.documentsSkipped).toBe(0)
    expect(state.isPolling).toBe(false)
  })

  it('closes snackbar', () => {
    useIndexingStore.setState({
      snackbar: { open: true, message: 'Test', severity: 'success' },
    })
    useIndexingStore.getState().closeSnackbar()
    expect(useIndexingStore.getState().snackbar.open).toBe(false)
  })

  it('triggers indexing for the given library and starts polling', async () => {
    // 'library-personal' is UPLOAD and has no run type at all (see the dedicated 409 test below) -
    // 'library-referat-50' (FILESYSTEM) is the fixture with an actual indexing run.
    vi.useFakeTimers()

    await useIndexingStore.getState().triggerIndexing('library-referat-50')

    const state = useIndexingStore.getState()
    expect(state.status).toBe('RUNNING')
    expect(state.isPolling).toBe(true)

    useIndexingStore.getState().stopPolling()
    vi.useRealTimers()
  })

  it('stops polling', async () => {
    vi.useFakeTimers()

    await useIndexingStore.getState().triggerIndexing('library-referat-50')
    expect(useIndexingStore.getState().isPolling).toBe(true)

    useIndexingStore.getState().stopPolling()
    expect(useIndexingStore.getState().isPolling).toBe(false)

    vi.useRealTimers()
  })

  it('shows a specific message and leaves status untouched when the target is an UPLOAD library', async () => {
    // #500 review, finding 5: an UPLOAD library has no run type at all (backend 409) - no run was
    // ever started, so overwriting status to FAILED would misleadingly suggest one broke.
    await useIndexingStore.getState().triggerIndexing('library-personal')

    const state = useIndexingStore.getState()
    expect(state.status).toBe('IDLE')
    expect(state.snackbar.open).toBe(true)
    expect(state.snackbar.severity).toBe('error')
    expect(state.snackbar.message).toBe('Fuer UPLOAD-Bibliotheken gibt es keinen Indizierungslauf')
  })

  it('loads the current status for a library and starts polling if a run is already active', async () => {
    vi.useFakeTimers()
    await useIndexingStore.getState().triggerIndexing('library-referat-50')
    useIndexingStore.getState().stopPolling()
    useIndexingStore.setState({ status: 'IDLE' })

    await useIndexingStore.getState().loadStatus('library-referat-50')

    expect(useIndexingStore.getState().status).toBe('RUNNING')
    expect(useIndexingStore.getState().isPolling).toBe(true)

    useIndexingStore.getState().stopPolling()
    vi.useRealTimers()
  })
})
