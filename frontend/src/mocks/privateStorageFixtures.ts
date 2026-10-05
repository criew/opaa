import type { PrivateStorageSummaryResponse, PrivateStorageUsageResponse } from '../types/api'

const GIB = 1024 * 1024 * 1024

/** The configured default limit across all private libraries of a person. */
export const MOCK_DEFAULT_PRIVATE_QUOTA_BYTES = 10 * GIB

/** Use of the signed-in person across her private libraries. */
export const mockMyPrivateStorage: PrivateStorageUsageResponse = {
  usedBytes: 2.4 * GIB,
  quotaBytes: MOCK_DEFAULT_PRIVATE_QUOTA_BYTES,
}

/** Masked sums as the backend tells them: one exact part, one not told, one run end below N. */
export const mockPrivateStorageSummary: Omit<PrivateStorageSummaryResponse, 'quotaBytes'> = {
  owners: { value: 14 },
  usedBytes: { value: 31 * GIB },
  profiles: [
    {
      profileId: 'connection-profile-nextcloud-personen',
      name: 'Nextcloud Personen',
      usedBytes: { value: 24 * GIB },
    },
    { profileId: 'connection-profile-smb-person', name: 'Dateiserver Personen', usedBytes: {} },
  ],
  runWindowDays: 30,
  runEnds: [
    { category: 'QUOTA_EXHAUSTED', runs: { value: 6 } },
    { category: 'EXPIRED', runs: { fewerThanPersons: 5 } },
  ],
}
