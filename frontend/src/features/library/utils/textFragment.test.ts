import { describe, expect, it } from 'vitest'
import { withTextFragment } from './textFragment'

describe('withTextFragment', () => {
  it('uses the first and last words of the passage as encoded terms', () => {
    expect(
      withTextFragment('https://wiki.example/x', {
        quoteStart: 'Zuständig ist die Stelle, A&B',
        quoteEnd: 'die Frist endet zwei-wöchig.',
      }),
    ).toBe(
      'https://wiki.example/x#:~:text=Zust%C3%A4ndig%20ist%20die%20Stelle%2C%20A%26B,die%20Frist%20endet%20zwei%2Dw%C3%B6chig.',
    )
  })

  it('keeps to a few words so a term stays inside one block of the page', () => {
    expect(
      withTextFragment('https://wiki.example/x', {
        quoteStart: 'Eins zwei drei vier fünf sechs sieben',
      }),
    ).toBe('https://wiki.example/x#:~:text=Eins%20zwei%20drei%20vier%20f%C3%BCnf')
  })

  it('skips list numbers, bullets and table separators the page does not show', () => {
    expect(
      withTextFragment('https://wiki.example/x', {
        quoteStart: '2.1. Antrag stellen | Frist',
        quoteEnd: 'Ende • Anhang',
      }),
    ).toBe('https://wiki.example/x#:~:text=Antrag%20stellen,Anhang')
  })

  it('leaves out the word a full-length quote may have cut', () => {
    const start = 'Abschnitt mit einem sehr langen Kompositum Verwaltungsvorsch'
    const end = 'sungsvorschrift endet hier mit einem ganz normalen Satzende.'
    expect(start.length).toBe(60)
    expect(end.length).toBeGreaterThanOrEqual(60)

    const url = withTextFragment('https://wiki.example/x', { quoteStart: start, quoteEnd: end })

    expect(url).toBe(
      'https://wiki.example/x#:~:text=Abschnitt%20mit%20einem%20sehr%20langen,mit%20einem%20ganz%20normalen%20Satzende.',
    )
  })

  it('keeps an existing fragment', () => {
    expect(withTextFragment('https://wiki.example/x#abschnitt', { quoteStart: 'Kurz' })).toBe(
      'https://wiki.example/x#abschnitt:~:text=Kurz',
    )
  })

  it('leaves the address alone without a usable quote', () => {
    expect(withTextFragment('https://wiki.example/x', null)).toBe('https://wiki.example/x')
    expect(withTextFragment('https://wiki.example/x', { quoteStart: ' - ' })).toBe(
      'https://wiki.example/x',
    )
  })
})
