/** One object of a source as a listing offers it: a site, a library, a folder, a space. */
export interface PickerEntry {
  key: string
  name: string
  /** One short secondary fact (the kind of a Drive area); never repeats the name. */
  hint?: string | null
  /** Whether the entry itself can be chosen; a site only leads to its libraries. */
  selectable: boolean
  /** Whether the entry can be opened to list what lies below it. */
  hasChildren: boolean
  /** Connector data the next listing needs (a drive id, a site URL). */
  raw?: Record<string, unknown>
}

/** A step of the path from the root to the level shown. */
export interface PickerCrumb {
  key: string
  name: string
  raw?: Record<string, unknown>
}

/** What a listing of one level returned. */
export interface PickerLevel {
  entries: PickerEntry[]
  /** False when the source stopped the listing early; `message` then says why. */
  complete: boolean
  message?: string | null
}

/** A chosen object with the path it was chosen under, so its full path can be shown. */
export interface PickedItem {
  key: string
  name: string
  trail: PickerCrumb[]
  raw?: Record<string, unknown>
}

export interface PickerNoun {
  one: string
  many: string
}

export function countOf(count: number, noun: PickerNoun): string {
  return `${count.toLocaleString('de-DE')} ${count === 1 ? noun.one : noun.many}`
}

/** The full path of a chosen object, from the root label down to its name. */
export function pathOf(item: PickedItem, rootLabel: string): string {
  return [rootLabel, ...item.trail.map((crumb) => crumb.name), item.name].join(' › ')
}
