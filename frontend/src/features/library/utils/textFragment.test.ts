import { describe, expect, it } from 'vitest'
import { withTextFragment } from './textFragment'

describe('withTextFragment', () => {
  it('adds start and end of the passage as an encoded text directive', () => {
    expect(
      withTextFragment('https://wiki.example/x', {
        quoteStart: 'Frist - zwei Wochen, ab  Zustellung & Eingang',
        quoteEnd: 'endet hier.',
      }),
    ).toBe(
      'https://wiki.example/x#:~:text=Frist%20%2D%20zwei%20Wochen%2C%20ab%20Zustellung%20%26%20Eingang,endet%20hier.',
    )
  })

  it('uses the start alone for a short passage without end quote', () => {
    expect(withTextFragment('https://wiki.example/x', { quoteStart: 'Kurz' })).toBe(
      'https://wiki.example/x#:~:text=Kurz',
    )
  })

  it('keeps an existing fragment', () => {
    expect(withTextFragment('https://wiki.example/x#abschnitt', { quoteStart: 'Kurz' })).toBe(
      'https://wiki.example/x#abschnitt:~:text=Kurz',
    )
  })

  it('leaves the address alone without a quote', () => {
    expect(withTextFragment('https://wiki.example/x', null)).toBe('https://wiki.example/x')
    expect(withTextFragment('https://wiki.example/x', { quoteStart: ' ' })).toBe(
      'https://wiki.example/x',
    )
  })
})
