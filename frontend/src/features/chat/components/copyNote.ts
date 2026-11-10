import { formatDate } from '@/shared/utils/formatDate'

/**
 * The note appended when an answer is copied with the copy button: the authority's notice from
 * the branding, the product name and the date of the answer (German format). Deliberately without
 * a model name - that would reveal the installation's configuration. It is visible text, not a
 * machine-readable marking.
 */
export function copyNote(notice: string, productName: string, answeredAt: Date): string {
  const date = formatDate(answeredAt)
  return `${plainNoteText(notice)} – ${plainNoteText(productName)}, ${date}`
}

/**
 * Notice and product name come from the branding. Direction controls (U+202A–202E, U+2066–2069,
 * U+200E/F, U+061C) would reorder the rest of the line when the note is pasted, tag characters
 * (U+E0000–E007F) could hide text in it, and breaks or control characters would split it, so they
 * are dropped or turned into blanks. The PDF export of a chat applies the same rule.
 */
function plainNoteText(text: string): string {
  return Array.from(text.replace(/[\r\n\u2028\u2029]+/g, ' '))
    .filter((char) => !isDroppedFromNote(char.codePointAt(0) ?? 0))
    .join('')
    .trim()
}

function isDroppedFromNote(code: number): boolean {
  return (
    code <= 0x1f ||
    (code >= 0x7f && code <= 0x9f) ||
    code === 0x61c ||
    code === 0x200e ||
    code === 0x200f ||
    (code >= 0x202a && code <= 0x202e) ||
    (code >= 0x2066 && code <= 0x2069) ||
    (code >= 0xe0000 && code <= 0xe007f)
  )
}
