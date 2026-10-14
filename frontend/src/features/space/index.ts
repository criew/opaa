export { getSpaces } from './api/spaceApi'
export {
  spaceKnowledgeGap,
  NO_KNOWLEDGE_ASSIGNED,
  NO_KNOWLEDGE_READABLE,
} from './components/spaceKnowledge'
export { default as SpaceKnowledgeNotice } from './components/SpaceKnowledgeNotice'
export { useSpaceStore } from './stores/spaceStore'
export {
  rememberSpaceUse,
  spacesByRecentUse,
  useRecentSpaceIds,
  lastUsedSpace,
} from './utils/recentSpaces'
