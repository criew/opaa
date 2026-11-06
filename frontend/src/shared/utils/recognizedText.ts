/** The sentence every view shows beside a passage whose text came from text recognition (OCR). */
export const RECOGNIZED_TEXT_NOTICE = 'Maschinell erkannt, maßgeblich ist das Original'

/**
 * Whether an anchor - typed, or the raw `anchor` metadatum of a stored chunk - says its text was
 * read by text recognition rather than taken from the document's own text layer.
 */
export function isRecognizedText(anchor: unknown): boolean {
  if (anchor === null || typeof anchor !== 'object') return false
  const origin = (anchor as { textOrigin?: unknown }).textOrigin
  return origin !== null && typeof origin === 'object'
}
