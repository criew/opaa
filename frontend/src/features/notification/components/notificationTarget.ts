import type { NotificationResponse } from '@/shared/api/types'
import {
  CONNECTED_ACCOUNTS_ROUTE,
  CONNECTION_PROFILES_ROUTE,
  EXTERNAL_TOOLS_TOKENS_ROUTE,
  connectionProfileRoute,
} from '@/shared/routes'

/** Where a notification leads when clicked; `null` keeps it a plain entry. */
export function notificationTarget({
  type,
  objectId,
}: Pick<NotificationResponse, 'type' | 'objectId'>): string | null {
  switch (type) {
    case 'CONNECTION_EXPIRED':
    case 'CONNECTION_ENDED':
    case 'CONNECTION_EXPIRING':
      return CONNECTED_ACCOUNTS_ROUTE
    // the object is the profile whose secret expires
    case 'CONNECTION_PROFILE_SECRET_EXPIRING':
      return objectId ? connectionProfileRoute(objectId) : CONNECTION_PROFILES_ROUTE
    // the list only, never narrowed to the token's person: the alert serves an incident, not a
    // look at one employee
    case 'EXTERNAL_ACCESS_MASS_RETRIEVAL':
      return EXTERNAL_TOOLS_TOKENS_ROUTE
    // the object is the library whose own source connection is meant
    case 'SOURCE_CONNECTION_EXPIRING':
    case 'SOURCE_CONNECTION_EXPIRED':
    case 'SOURCE_CONNECTION_ENDED':
      return objectId ? `/libraries/${objectId}?tab=quelle` : null
    case 'ASSET_ASSOCIATED_TO_MIXED_SPACE':
    case 'GROUP_MEMBER_ADDED':
    case 'GROUP_MEMBER_REMOVED':
    case 'PRIVATE_LIBRARY_RELEASED':
    case 'SOURCE_FULL_SYNC_FORCED':
      return null
    default: {
      const unknown: never = type
      throw new Error(`Unknown notification type ${String(unknown)}`)
    }
  }
}
