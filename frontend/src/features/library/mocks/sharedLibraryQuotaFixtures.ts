const GIB = 1024 * 1024 * 1024

/** The operator's upper limit per library (OPAA_LIBRARY_QUOTA_BYTES). */
export const MOCK_LIBRARY_QUOTA_CAP_BYTES = 10 * GIB

/** What the shared libraries of the mock installation occupy, one each. */
export const MOCK_SHARED_LIBRARY_USAGE_BYTES = [0.4 * GIB, 2.6 * GIB, 6 * GIB]
