import type { S3ScopeInput } from '../../../utils/s3Source'
import type { PickNode } from '../PickCombo'

/** A prefix of `bucket` („a/b/“) as a node below the bucket. */
export function s3PrefixNode(bucket: string, prefix: string): PickNode {
  const segments = prefix.split('/').filter(Boolean)
  return {
    key: `${bucket}/${segments.join('/')}/`,
    label: segments[segments.length - 1] ?? bucket,
    trail: [bucket, ...segments.slice(0, -1)],
    branch: true,
  }
}

export function s3Nodes(scopes: S3ScopeInput[]): PickNode[] {
  return scopes
    .filter((scope) => scope.bucket.trim() !== '')
    .map((scope) =>
      scope.prefix.trim()
        ? s3PrefixNode(scope.bucket.trim(), scope.prefix)
        : { key: scope.bucket.trim(), label: scope.bucket.trim(), trail: [], branch: true },
    )
}

export function s3ScopeFromNode(node: PickNode): S3ScopeInput {
  const [bucket, ...rest] = [...node.trail, node.label]
  return { bucket, prefix: rest.length ? `${rest.join('/')}/` : '' }
}
