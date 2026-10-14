// Assignment of the files under src/ to the buckets of themes.js; generated API types excluded.
import fs from 'node:fs'
import path from 'node:path'
import micromatch from 'micromatch'
import { APP, SHARED, TEST_INFRA, THEMES, bucketGlobs, themeGlobs } from './themes.js'

const GENERATED = 'src/types/generated/'

export const BUCKETS = {
  ...Object.fromEntries(Object.keys(THEMES).map((name) => [name, themeGlobs(name)])),
  shared: bucketGlobs(SHARED),
  app: bucketGlobs(APP),
  'test-infra': bucketGlobs(TEST_INFRA),
}

/** All files below `dir` (relative to root), as posix paths relative to root. */
export function listSourceFiles(root, dir = 'src') {
  return fs.readdirSync(path.join(root, dir), { withFileTypes: true }).flatMap((entry) => {
    const file = `${dir}/${entry.name}`
    return entry.isDirectory() ? listSourceFiles(root, file) : [file]
  })
}

/** Files matched by no bucket or by more than one, with the buckets they matched. */
export function findMisassigned(root) {
  return listSourceFiles(root)
    .filter((file) => !file.startsWith(GENERATED))
    .map((file) => ({
      file,
      buckets: Object.keys(BUCKETS).filter((name) => micromatch.isMatch(file, BUCKETS[name])),
    }))
    .filter(({ buckets }) => buckets.length !== 1)
}
