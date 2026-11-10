/** Marks the page header, whose own bottom margin is the gap to the first section. */
export const AREA_PAGE_HEADER_MARK = { 'data-area-page-header': '' } as const

/**
 * A section head that follows other content in the flow of a page carries the gap to it
 * (guidelines 4.6). The first one in its container and the one right under the page header do
 * not: there the container or the header sets the distance. `:where()` keeps the rule at the
 * weight of a single class, so a `Stack` - the layout of dialogs and wizard steps - keeps its own
 * spacing between its children.
 */
export const SECTION_GAP_SELECTOR = '&:where(:not(:first-child):not([data-area-page-header] + *))'
