/**
 * Formatting shared by the parts of the "Suche & Indexierung" page - and, for {@link plural},
 * by every other place that counts documents in German.
 */

export const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** A document key is only a loadable document id when it is a UUID; other keys stay plain text. */
export function isUuid(value: string) {
  return UUID_PATTERN.test(value)
}

/** German singular/plural, so the page never says "1 Bibliotheken". */
export function plural(count: number, one: string, many: string) {
  return `${count} ${count === 1 ? one : many}`
}
