import { createContext, useContext } from 'react'

/** Set below `PlainPageSections`: sections head themselves in running text (detail tabs). */
export const PlainSectionContext = createContext(false)

/** Whether the sections around a component head themselves in running text (detail tabs). */
export function usePlainSections(): boolean {
  return useContext(PlainSectionContext)
}
