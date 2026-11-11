import type { ReactNode } from 'react'
import { ChatList } from '@/features/chat'
import type { SpaceListLoader } from '@/features/asset'
import { getSpaces } from '@/features/space'
import QuickTourHint from './tour/QuickTourHint'

/** The space list asset components offer and check against, provided via SpaceListContext. */
export const loadSpaceList: SpaceListLoader = getSpaces

/** The chat list SpacePage shows in its Chats section. */
export function renderSpaceChats(spaceId: string): ReactNode {
  return <ChatList spaceId={spaceId} />
}

/** What the empty chat shows below its greeting: the hint of the quick tour, if it is due. */
export const emptyChatHint: ReactNode = <QuickTourHint />
