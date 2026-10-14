/** Dictated text goes after whatever is already typed, separated by one space. */
export function withDictatedText(draft: string, dictated: string): string {
  if (draft.trim() === '') return dictated
  return /\s$/.test(draft) ? `${draft}${dictated}` : `${draft} ${dictated}`
}
