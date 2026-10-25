import { renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { listLibraryMetadataFields } from '../api/libraryMetadataApi'
import { useNextSteps } from './nextSteps'

vi.mock('../api/libraryMetadataApi', () => ({
  listLibraryMetadataFields: vi.fn(),
}))

const listFields = vi.mocked(listLibraryMetadataFields)

type Input = Parameters<typeof useNextSteps>[0]

function input(overrides: Partial<Input> = {}): Input {
  return {
    libraryId: 'library-1',
    sourceType: 'UPLOAD',
    justCreated: true,
    mayChange: true,
    documentCount: 0,
    spaceCount: 0,
    pushSecretSet: false,
    sourceConnected: false,
    metadataEnabled: false,
    ...overrides,
  }
}

function fieldsResponse(count: number) {
  return {
    items: Array.from({ length: count }, (_, i) => ({ fieldKey: `field-${i}` })),
  } as unknown as Awaited<ReturnType<typeof listLibraryMetadataFields>>
}

function labels(result: { current: ReturnType<typeof useNextSteps> }) {
  return result.current.steps.map((step) => step.label)
}

beforeEach(() => {
  localStorage.clear()
  sessionStorage.clear()
  listFields.mockReset()
  listFields.mockResolvedValue(fieldsResponse(0))
})

describe('useNextSteps', () => {
  it('shows the steps right after the wizard handed over', () => {
    const { result } = renderHook(() => useNextSteps(input()))

    expect(labels(result)).toEqual(['Dateien hochladen', 'In Space verwenden'])
  })

  it('shows no steps without the handover and without a stored open list', () => {
    const { result } = renderHook(() => useNextSteps(input({ justCreated: false })))

    expect(result.current.steps).toEqual([])
  })

  it('shows the steps of a stored open list without the handover', () => {
    sessionStorage.setItem('opaa-next-steps:library-1', 'open')

    const { result } = renderHook(() => useNextSteps(input({ justCreated: false })))

    expect(labels(result)).toEqual(['Dateien hochladen', 'In Space verwenden'])
  })

  it('shows no steps to whoever may not change the library', () => {
    const { result } = renderHook(() => useNextSteps(input({ mayChange: false })))

    expect(result.current.steps).toEqual([])
  })

  // regression guard: the steps vanished after the first link dropped `justCreated`
  it('stores the handover at once, so the list outlives the loss of justCreated', () => {
    const { result, rerender } = renderHook((props: Input) => useNextSteps(props), {
      initialProps: input(),
    })

    expect(sessionStorage.getItem('opaa-next-steps:library-1')).toBe('open')
    expect(localStorage.getItem('opaa-next-steps:library-1')).toBeNull()

    rerender(input({ justCreated: false }))
    expect(labels(result)).toEqual(['Dateien hochladen', 'In Space verwenden'])
  })

  it('keeps a dismissed list away for good, even with a new handover', () => {
    const { result, unmount } = renderHook(() => useNextSteps(input()))

    result.current.dismiss()
    unmount()

    expect(localStorage.getItem('opaa-next-steps:library-1')).toBe('dismissed')
    const again = renderHook(() => useNextSteps(input({ justCreated: true })))
    expect(again.result.current.steps).toEqual([])
  })

  it('removes the list at once when dismissed', async () => {
    const { result } = renderHook(() => useNextSteps(input()))

    result.current.dismiss()

    await waitFor(() => expect(result.current.steps).toEqual([]))
  })

  it('shows no steps once every step is done', () => {
    const { result } = renderHook(() => useNextSteps(input({ documentCount: 3, spaceCount: 1 })))

    expect(result.current.steps).toEqual([])
  })

  it('marks single steps as done and keeps the open ones', () => {
    const { result } = renderHook(() => useNextSteps(input({ documentCount: 3 })))

    expect(result.current.steps.map((step) => [step.key, step.done])).toEqual([
      ['upload', true],
      ['space', false],
    ])
  })

  it('offers „Zugang prüfen“ for a connector whose source does not answer yet', () => {
    const { result } = renderHook(() => useNextSteps(input({ sourceType: 'NEXTCLOUD' })))

    expect(result.current.steps[0]).toMatchObject({
      key: 'source',
      label: 'Zugang prüfen',
      done: false,
      tab: 'quelle',
      row: 'connection',
    })
    expect(labels(result)).toEqual(['Zugang prüfen', 'In Space verwenden'])
  })

  it('ticks „Quelle verbunden“ for a connector whose source answers', () => {
    const { result } = renderHook(() =>
      useNextSteps(input({ sourceType: 'NEXTCLOUD', sourceConnected: true })),
    )

    expect(result.current.steps[0]).toMatchObject({
      key: 'source',
      label: 'Quelle verbunden',
      done: true,
    })
    expect(labels(result)).not.toContain('Dateien hochladen')
  })

  it('offers „Live-Abgleich einrichten“ only for Confluence and S3', () => {
    for (const sourceType of ['CONFLUENCE', 'S3'] as const) {
      const { result, unmount } = renderHook(() => useNextSteps(input({ sourceType })))
      expect(result.current.steps.at(-1)).toMatchObject({
        key: 'live',
        label: 'Live-Abgleich einrichten',
        row: 'live',
        done: false,
      })
      unmount()
    }
    for (const sourceType of ['UPLOAD', 'NEXTCLOUD'] as const) {
      const { result, unmount } = renderHook(() => useNextSteps(input({ sourceType })))
      expect(labels(result)).not.toContain('Live-Abgleich einrichten')
      unmount()
    }
  })

  it('ticks „Live-Abgleich einrichten“ once the push secret is set', () => {
    const { result } = renderHook(() =>
      useNextSteps(input({ sourceType: 'S3', pushSecretSet: true })),
    )

    expect(result.current.steps.find((step) => step.key === 'live')?.done).toBe(true)
  })

  it('offers „Feld anlegen“ only with metadata enabled', async () => {
    const without = renderHook(() => useNextSteps(input()))
    expect(labels(without.result)).not.toContain('Feld anlegen')
    expect(listFields).not.toHaveBeenCalled()
    without.unmount()

    const withMetadata = renderHook(() => useNextSteps(input({ metadataEnabled: true })))
    expect(labels(withMetadata.result)).toEqual([
      'Dateien hochladen',
      'In Space verwenden',
      'Feld anlegen',
    ])
    await waitFor(() => expect(listFields).toHaveBeenCalledWith('library-1'))
  })

  it('refetches the field count on a new fieldsVersion and ticks „Feld anlegen“ without remount', async () => {
    const { result, rerender } = renderHook((props: Input) => useNextSteps(props), {
      initialProps: input({ metadataEnabled: true, fieldsVersion: 0 }),
    })
    await waitFor(() => expect(listFields).toHaveBeenCalledTimes(1))
    expect(result.current.steps.find((step) => step.key === 'metadata')?.done).toBe(false)

    listFields.mockResolvedValue(fieldsResponse(1))
    rerender(input({ metadataEnabled: true, fieldsVersion: 1 }))

    await waitFor(() =>
      expect(result.current.steps.find((step) => step.key === 'metadata')?.done).toBe(true),
    )
    expect(listFields).toHaveBeenCalledTimes(2)
  })
})
