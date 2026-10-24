let loading = 0

/** True while an optional chunk is being loaded through {@link loadOptionalChunk}. */
export function isOptionalChunkLoading(): boolean {
  return loading > 0
}

/**
 * Loads a chunk that only adds to what the user is doing (the HTML flavour of a copied answer) and
 * has a fallback. While it loads, the page reload that answers a missing chunk after a deploy
 * (app/preloadErrorReload) stays off: the import is rejected instead, the caller falls back, and
 * the user's action completes without the page vanishing underneath it.
 */
export async function loadOptionalChunk<T>(load: () => Promise<T>): Promise<T> {
  loading += 1
  try {
    return await load()
  } finally {
    loading -= 1
  }
}
