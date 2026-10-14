import { createContext, useContext } from 'react'
import type { SpaceListResponse } from '@/shared/api/types'

/** Loads the spaces visible to the signed-in person, each with the person's own role. */
export type SpaceListLoader = () => Promise<SpaceListResponse[]>

const noSpaceList: SpaceListLoader = () =>
  Promise.reject(new Error('No space list loader provided via SpaceListContext'))

/**
 * Hands asset components the space list they offer and check against ("In Space verwenden",
 * detaching). The asset theme cannot reach the space theme; the application frame provides it.
 */
export const SpaceListContext = createContext<SpaceListLoader>(noSpaceList)

export function useSpaceListLoader(): SpaceListLoader {
  return useContext(SpaceListContext)
}
