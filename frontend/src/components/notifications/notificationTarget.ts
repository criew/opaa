import type { NotificationType } from '../../types/api'
import { CONNECTED_ACCOUNTS_ROUTE } from '../../routes'

/** Where a notification of this type leads when clicked; `null` keeps it a plain entry. */
export function notificationTarget(type: NotificationType): string | null {
  switch (type) {
    case 'CONNECTION_EXPIRED':
    case 'CONNECTION_ENDED':
      return CONNECTED_ACCOUNTS_ROUTE
    case 'ASSET_ASSOCIATED_TO_MIXED_SPACE':
    case 'EXTERNAL_ACCESS_MASS_RETRIEVAL':
    case 'GROUP_MEMBER_ADDED':
    case 'GROUP_MEMBER_REMOVED':
    case 'CONNECTION_PROFILE_REQUESTED':
    case 'CONNECTION_PROFILE_REQUEST_RESOLVED':
    case 'PRIVATE_LIBRARY_RELEASED':
    case 'SOURCE_FULL_SYNC_FORCED':
      return null
    default: {
      const unknown: never = type
      throw new Error(`Unknown notification type ${String(unknown)}`)
    }
  }
}
