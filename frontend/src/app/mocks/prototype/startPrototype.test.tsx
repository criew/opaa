import { screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { installInPageMocks } from '../inPageMocks'
import { startPrototype } from './startPrototype'

vi.mock('../inPageMocks', () => ({ installInPageMocks: vi.fn() }))
vi.mock('../handlers', () => ({ handlers: [] }))

describe('startPrototype', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    document.body.innerHTML = '<div id="root"></div>'
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.mocked(installInPageMocks).mockClear()
    window.history.replaceState(null, '', '/')
    document.body.innerHTML = ''
  })

  it('renders the app in this document at normal width, next to the bar', async () => {
    window.history.replaceState(null, '', '/proto/index.html#/catalog')

    expect(startPrototype()).toBe(true)

    expect(installInPageMocks).toHaveBeenCalledTimes(1)
    expect(document.querySelector('iframe')).toBeNull()
    vi.useRealTimers()
    expect(await screen.findByRole('complementary', { name: 'Prototyp-Leiste' })).toBeVisible()
  })

  it('moves the app into a 320 px frame of the same page at the narrow width', () => {
    window.history.replaceState(
      null,
      '',
      '/proto/index.html?protoWidth=320&mockScenario=scale#/libraries/x',
    )

    expect(startPrototype()).toBe(false)

    const frame = document.querySelector('#root > iframe') as HTMLIFrameElement
    expect(frame.style.width).toBe('320px')
    expect(frame.getAttribute('src')).toBe(
      '/proto/index.html?mockScenario=scale&protoFrame=1#/libraries/x',
    )
  })

  it('renders only the app inside the narrow frame, without a second bar', () => {
    window.history.replaceState(null, '', '/proto/index.html?protoFrame=1#/catalog')

    expect(startPrototype()).toBe(true)

    expect(installInPageMocks).toHaveBeenCalledTimes(1)
    expect(document.body.children).toHaveLength(1)
  })
})
