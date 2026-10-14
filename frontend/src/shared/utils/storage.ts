/** Reads a Web Storage entry; unavailable storage (private mode, blocked) reads as absent. */
export function readStorage(storage: Storage, key: string): string | null {
  try {
    return storage.getItem(key)
  } catch {
    return null
  }
}

/** Writes or, for null, removes an entry. Returns false if the storage is unavailable. */
export function writeStorage(storage: Storage, key: string, value: string | null): boolean {
  try {
    if (value === null) storage.removeItem(key)
    else storage.setItem(key, value)
    return true
  } catch {
    return false
  }
}
