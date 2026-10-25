import type { ConnectionLifecycleSettingResponse } from '@/shared/api/types'

/** The settings at their factory defaults, as an installation the administration never set. */
export const mockConnectionLifecycleDefaults: ConnectionLifecycleSettingResponse[] = [
  {
    setting: 'INACTIVITY_THRESHOLD_DAYS',
    value: 90,
    defaultValue: 90,
    minValue: 30,
    maxValue: 365,
    overridden: false,
    updatedAt: null,
    pausedBy: [],
  },
  {
    setting: 'PRIVATE_LIBRARY_DELETION_DAYS',
    value: 30,
    defaultValue: 30,
    minValue: 1,
    maxValue: 90,
    overridden: false,
    updatedAt: null,
    pausedBy: [],
  },
  {
    setting: 'EXPIRED_CONNECTION_WARNING_THRESHOLD',
    value: 10,
    defaultValue: 10,
    minValue: 1,
    maxValue: 1000,
    overridden: false,
    updatedAt: null,
    pausedBy: [],
  },
]
