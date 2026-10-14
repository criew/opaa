import type { ReactNode } from 'react'
import ChatList from '../components/chat/ChatList'
import type { SpaceListLoader } from '@/features/asset'
import { getSpaces } from '../services/spaceApi'

/** The space list asset components offer and check against, provided via SpaceListContext. */
export const loadSpaceList: SpaceListLoader = getSpaces

/** The chat list SpacePage shows in its Chats section. */
export function renderSpaceChats(spaceId: string): ReactNode {
  return <ChatList spaceId={spaceId} />
}
