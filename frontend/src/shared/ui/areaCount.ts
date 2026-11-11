/** Singular and plural of the counted object: `['Konto', 'Konten']`. */
export type CountUnit = readonly [one: string, many: string]

export interface AreaCountValue {
  /** All entries of the list, without any filter. */
  total: number
  /** The entries the current filter leaves; omitted or equal to `total` while nothing narrows. */
  filtered?: number
  unit: CountUnit
}

/**
 * The count next to an area page's title (guidelines 4.6): „1 Konto“, „40 Konten“, and
 * „12 von 40 Konten“ while a filter narrows the list.
 */
export function formatAreaCount({ total, filtered, unit }: AreaCountValue): string {
  const noun = total === 1 ? unit[0] : unit[1]
  if (filtered !== undefined && filtered !== total) return `${filtered} von ${total} ${noun}`
  return `${total} ${noun}`
}
