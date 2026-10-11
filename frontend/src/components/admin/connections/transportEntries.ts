import type { ConnectionProfileResponse } from '../../../types/api'
import type { KeyValueEntry } from '../../KeyValueList'

/**
 * Proxy and certificate check of a profile in its panel, only where set. Apart from the panel for
 * the same reason as TransportFields: they go with the house-wide outbound proxy (#2496).
 */
export function transportEntries(profile: ConnectionProfileResponse): KeyValueEntry[] {
  return [
    { label: 'Proxy', value: profile.sourceProxy ?? '', hidden: !profile.sourceProxy },
    {
      label: 'Zertifikatsprüfung',
      value: 'ausgesetzt',
      hidden: !profile.sourceInsecureSsl,
    },
  ]
}
