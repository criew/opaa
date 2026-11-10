import { chatTitle } from './chatListSections'

/** Characters a file system rejects, plus control characters - the server's rule for the PDF. */
// eslint-disable-next-line no-control-regex
const UNSAFE_FILE_NAME = /[\\/:*?"<>|\u0000-\u001f\u007f]/g

/**
 * The download name of an export: the chat's title, cleaned like the server cleans it - without
 * invisible format characters and cut to 80 code points.
 */
export function exportFileName(title: string | null | undefined, extension: 'md' | 'pdf'): string {
  let name = chatTitle({ title })
    .replace(/\p{Cf}/gu, '')
    .replace(UNSAFE_FILE_NAME, ' ')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/^\.+/, '')
    .trim()
  const codePoints = Array.from(name)
  if (codePoints.length > 80) name = codePoints.slice(0, 80).join('').trim()
  return `${name || 'Chat'}.${extension}`
}
