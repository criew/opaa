import type { NotificationType } from '../../types/api'
import { CONNECTED_ACCOUNTS_ROUTE, CONNECTION_PROFILES_ROUTE } from '../../routes'

/** Where a notification of this type leads when clicked; `null` keeps it a plain entry. */
export function notificationTarget(type: NotificationType): string | null {
  switch (type) {
    case 'CONNECTION_EXPIRED':
    case 'CONNECTION_ENDED':
    case 'CONNECTION_EXPIRING':
      return CONNECTED_ACCOUNTS_ROUTE
    case 'CONNECTION_PROFILE_SECRET_EXPIRING':
      return CONNECTION_PROFILES_ROUTE
    case 'ASSET_ASSOCIATED_TO_MIXED_SPACE':
    case 'EXTERNAL_ACCESS_MASS_RETRIEVAL':
    case 'GROUP_MEMBER_ADDED':
    case 'GROUP_MEMBER_REMOVED':
    case 'CONNECTION_PROFILE_REQUESTED':
    case 'CONNECTION_PROFILE_REQUEST_RESOLVED':
    case 'PRIVATE_LIBRARY_RELEASED':
    case 'SOURCE_FULL_SYNC_FORCED':
    case 'SOURCE_CONNECTION_EXPIRING':
    case 'SOURCE_CONNECTION_EXPIRED':
    case 'SOURCE_CONNECTION_ENDED':
      return null
    default: {
      const unknown: never = type
      throw new Error(`Unknown notification type ${String(unknown)}`)
    }
  }
}
