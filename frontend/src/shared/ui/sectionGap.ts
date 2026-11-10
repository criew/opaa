/** Marks the page header, whose own bottom margin is the gap to the first section. */
export const AREA_PAGE_HEADER_MARK = { 'data-area-page-header': '' } as const

/**
 * A section head that follows other content carries the gap to it (guidelines 4.6). The first one
 * in its container and the one right under the page header do not: there the container or the
 * header sets the distance.
 */
export const SECTION_GAP_SELECTOR = '&:not(:first-child):not([data-area-page-header] + &)'
