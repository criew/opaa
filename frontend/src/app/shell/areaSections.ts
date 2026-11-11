export interface GlobalAreaSection {
  label: string
  to: string
}

/**
 * Whether a section is the current one: prefix match like the rail, so a subroute keeps its
 * section marked - `aria-current="page"` on the section itself, `"true"` below it.
 */
export function sectionState(
  section: GlobalAreaSection,
  pathname: string,
): { active: boolean; current: 'page' | 'true' | undefined } {
  const exact = pathname === section.to
  const active = exact || pathname.startsWith(`${section.to}/`)
  return { active, current: active ? (exact ? 'page' : 'true') : undefined }
}
