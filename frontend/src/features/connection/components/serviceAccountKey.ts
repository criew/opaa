/** The upper bound of the key file the API accepts, in characters. */
export const MAX_GOOGLE_DRIVE_KEY_LENGTH = 4096

/**
 * The account of a key file, or the German reason it is none. Only `client_email` is read here;
 * the backend checks the key itself.
 */
export function readGoogleDriveKey(text: string): { account: string } | { error: string } {
  if (text.length > MAX_GOOGLE_DRIVE_KEY_LENGTH) {
    return { error: `Die Schlüsseldatei ist größer als ${MAX_GOOGLE_DRIVE_KEY_LENGTH} Zeichen.` }
  }
  try {
    const parsed = JSON.parse(text) as Record<string, unknown>
    if (
      typeof parsed.client_email === 'string' &&
      typeof parsed.private_key === 'string' &&
      typeof parsed.private_key_id === 'string'
    ) {
      return { account: parsed.client_email }
    }
  } catch {
    // reported below
  }
  return {
    error:
      'Die Datei ist keine JSON-Schlüsseldatei eines Dienstkontos (erwartet client_email, private_key_id und private_key).',
  }
}
