/** One level the selection dialog has opened: a site, a library, a folder, a bucket. */
export interface Crumb {
  key: string
  name: string
}

/**
 * One chosen object of a source. `trail` names the levels above it, outermost first, so the
 * dialog can mark a parent as partly chosen and the summary can show where the object lies.
 */
export interface SelectionItem {
  key: string
  name: string
  trail: Crumb[]
}

/** One row of a listed level. */
export interface BrowseEntry {
  key: string
  name: string
  /** Whether the row carries a checkbox; a SharePoint site only leads further down. */
  selectable: boolean
  /** Whether the row leads to a level below. */
  openable: boolean
  /** Why the object cannot be chosen although it is listed, shown under its name. */
  blocked?: string
}

export interface BrowseResult {
  entries: BrowseEntry[]
  /** False when the source listed only part of the level; `message` says why. */
  complete: boolean
  message: string | null
}

/** A way to reach an object the listing does not offer: typed by hand. */
export interface ManualEntry {
  label: string
  placeholder: string
  /** `add` chooses the typed object, `open` opens it as a level (a SharePoint site). */
  kind: 'add' | 'open'
  /** The object for the text at the open level, or the German reason it is none. */
  resolve: (text: string, level: Crumb[]) => Promise<SelectionItem | string>
}

/**
 * How one connector feeds the shared selection dialog (Variante E): what a level holds, whether
 * the source searches a level itself, and the nouns the dialog speaks in. A flat source has a
 * single level; a hierarchical one opens entries as levels below.
 */
export interface SelectionSource {
  /** The name of the outermost level in the breadcrumb, e.g. „Nextcloud“ or „Spaces“. */
  rootLabel: string
  /** Singular and plural of the chosen objects, e.g. ['Ordner', 'Ordner']. */
  noun: [string, string]
  list: (level: Crumb[], search: string) => Promise<BrowseResult>
  /** Whether `list` filters by the search on the server at this level; else the dialog filters. */
  serverSearch: (level: Crumb[]) => boolean
  /** The placeholder of the search field at this level. */
  searchLabel: (level: Crumb[]) => string
  manual?: ManualEntry
  /** What an empty selection means, when it means something (NC/SMB/Dropbox: the whole source). */
  emptyMeans?: string
  /** The most objects the backend accepts. */
  max?: number
  /** Whether choosing an object also covers what lies below it (folders); false for flat sources. */
  nested: boolean
}

export function noun(source: Pick<SelectionSource, 'noun'>, count: number) {
  return count === 1 ? source.noun[0] : source.noun[1]
}

/** Whether `item` lies below the object `key`. */
export function isBelow(item: SelectionItem, key: string) {
  return item.trail.some((crumb) => crumb.key === key)
}

/** The display path of an item: the levels above it and its own name. */
export function pathLabel(item: SelectionItem) {
  return [...item.trail.map((crumb) => crumb.name), item.name].join(' / ')
}

/**
 * The selection with `entry` at `level` switched. Choosing a parent takes its chosen descendants
 * into it (they are covered anyway); a descendant of a chosen parent cannot be switched alone.
 */
export function toggle(
  selection: SelectionItem[],
  entry: Pick<SelectionItem, 'key' | 'name'>,
  level: Crumb[],
): SelectionItem[] {
  if (selection.some((item) => item.key === entry.key)) {
    return selection.filter((item) => item.key !== entry.key)
  }
  return [
    ...selection.filter((item) => !isBelow(item, entry.key)),
    { key: entry.key, name: entry.name, trail: level },
  ]
}
