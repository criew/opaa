package io.opaa.indexing.source.s3;

import io.opaa.s3.S3AccessException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

/**
 * "Alle Buckets" as the key finds it now (ADR-0027, Entscheidung 2): of every bucket {@code
 * ListBuckets} names, the library reads those in the connection's region that the key may list. The
 * others - another region, no {@code s3:ListBucket}, gone since the listing - are left out with a
 * German reason, so they never hold back the reconciliation of the rest. Any other answer of the
 * store (throttling, a refused key, an unreachable endpoint) is the caller's exception: leaving a
 * bucket out removes what was indexed from it, which only a definite answer may do.
 */
final class S3AllBuckets {

  static final String OTHER_ENDPOINT = "anderer Endpoint: Region oder Adressstil passen nicht";
  static final String NOT_LISTABLE = "Auflisten nicht erlaubt, s3:ListBucket fehlt";
  static final String GONE = "nicht mehr vorhanden";
  static final String INVALID_NAME = "kein gültiger Bucket-Name";
  static final String NOT_PROBED = "nicht geprüft, das Zeitlimit war erreicht";

  /**
   * @param readable the buckets the library reads, in name order
   * @param outside the buckets the key sees but the library leaves out, name to reason
   */
  record Resolution(List<String> readable, Map<String, String> outside) {

    Resolution {
      readable = List.copyOf(readable);
      outside = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(outside));
    }

    int seen() {
      return readable.size() + outside.size();
    }

    /** "„a“ (Region us-east-1), „b“ (…)", the first {@link #NAMED} named, the rest counted. */
    String outsideNamed() {
      List<String> named = new ArrayList<>();
      outside.forEach((bucket, reason) -> named.add("„" + bucket + "“ (" + reason + ")"));
      int shown = Math.min(named.size(), NAMED);
      return String.join(", ", named.subList(0, shown))
          + (named.size() > shown ? " und " + (named.size() - shown) + " weitere" : "");
    }
  }

  /** At most this many left-out buckets are named in one message. */
  static final int NAMED = 20;

  private S3AllBuckets() {}

  /**
   * Resolves {@code listed} against {@code region} with one listing request per bucket in it.
   * {@code timeUp} stops probing; the buckets not probed by then count as left out.
   */
  static Resolution resolve(
      S3ObjectStore store, S3BucketListing.Listed listed, String region, BooleanSupplier timeUp)
      throws S3AccessException, InterruptedException {
    List<String> readable = new ArrayList<>();
    Map<String, String> outside = new LinkedHashMap<>();
    for (String name : new TreeSet<>(listed.names())) {
      S3Scope scope;
      try {
        scope = S3Scope.of(name, "");
      } catch (S3Scope.InvalidS3ScopeException e) {
        outside.put(name, INVALID_NAME);
        continue;
      }
      String bucketRegion = listed.regionOf(name);
      if (bucketRegion != null && !bucketRegion.equalsIgnoreCase(region)) {
        outside.put(name, "Region " + bucketRegion);
        continue;
      }
      if (timeUp.getAsBoolean()) {
        outside.put(name, NOT_PROBED);
        continue;
      }
      String reason = probe(store, scope);
      if (reason == null) {
        readable.add(name);
      } else {
        outside.put(name, reason);
      }
    }
    return new Resolution(readable, outside);
  }

  /** {@code null} when the key may list the bucket, else why the library leaves it out. */
  private static String probe(S3ObjectStore store, S3Scope scope)
      throws S3AccessException, InterruptedException {
    try {
      store.listObjects(scope, null);
      return null;
    } catch (S3AccessException.ListForbidden e) {
      return NOT_LISTABLE;
    } catch (S3AccessException.WrongRegionOrStyle e) {
      return OTHER_ENDPOINT;
    } catch (S3AccessException.BucketNotFound e) {
      return GONE;
    }
  }
}
