import { describe, expect, it } from 'vitest'
import { sharePointItemsOf, sharePointLibrariesOfItems } from './selectionSources'

const SITE =
  'contoso.sharepoint.com,2c712604-1370-44e7-a1f5-426573fda80a,2d2244c3-251a-49ea-93a8-39e1c3a060fe'

describe('SharePoint selection objects (ADR-0047)', () => {
  it('keeps a stored site without a name nameless, never naming it by its id', () => {
    const libraries = [
      {
        driveId: 'b!a',
        name: 'Dokumente',
        folders: [],
        site: { id: SITE, name: null },
      },
      {
        driveId: 'b!b',
        name: 'Akten',
        folders: [{ id: '01X', name: 'Ratsbeschlüsse' }],
        site: { id: SITE, name: null },
      },
    ]

    expect(sharePointLibrariesOfItems(sharePointItemsOf(libraries))).toEqual(libraries)
  })

  it('keeps a named site and the library name that already names it', () => {
    const libraries = [
      {
        driveId: 'b!a',
        name: 'Dokumente (Rathaus)',
        folders: [],
        site: { id: SITE, name: 'Rathaus' },
      },
    ]

    expect(sharePointLibrariesOfItems(sharePointItemsOf(libraries))).toEqual(libraries)
  })
})
