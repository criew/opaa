import type { ConnectionProfileResponse } from '../../../types/api'
import type { KeyValueEntry } from '../../KeyValueList'

/** The certificate check of a profile in its panel, only where it is skipped. */
export function transportEntries(profile: ConnectionProfileResponse): KeyValueEntry[] {
  return [
    {
      label: 'Zertifikatsprüfung',
      value: 'ausgesetzt',
      hidden: !profile.sourceInsecureSsl,
    },
  ]
}
