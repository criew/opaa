import { describe, expect, it } from 'vitest'
import { adoptTrail } from './selection'

describe('adoptTrail', () => {
  const site = { key: 'site:s-1', name: 'Rathaus' }
  const listed = [
    { key: 'drive:d-1', name: 'Dokumente', selectable: true, openable: true },
    { key: 'drive:d-2', name: 'Protokolle', selectable: true, openable: true },
  ]

  it('places a chosen object without its levels where the open level lists it', () => {
    const chosen = [
      { key: 'drive:d-1', name: 'Dokumente (Rathaus)', trail: [] },
      { key: 'folder:f-1', name: 'Akten', trail: [{ key: 'drive:d-2', name: 'Protokolle' }] },
      { key: 'drive:d-9', name: 'Woanders', trail: [] },
    ]

    expect(adoptTrail(chosen, listed, [site])).toEqual([
      { key: 'drive:d-1', name: 'Dokumente (Rathaus)', trail: [site] },
      {
        key: 'folder:f-1',
        name: 'Akten',
        trail: [site, { key: 'drive:d-2', name: 'Protokolle' }],
      },
      { key: 'drive:d-9', name: 'Woanders', trail: [] },
    ])
  })

  it('leaves objects with their levels and the outermost level alone', () => {
    const placed = [{ key: 'drive:d-1', name: 'Dokumente', trail: [site] }]
    const loose = [{ key: 'drive:d-1', name: 'Dokumente', trail: [] }]

    expect(adoptTrail(placed, listed, [site])).toBe(placed)
    expect(adoptTrail(loose, listed, [])).toBe(loose)
  })
})
