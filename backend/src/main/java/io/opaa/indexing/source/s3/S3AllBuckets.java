package io.opaa.indexing.source.s3;

import io.opaa.s3.S3AccessException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;

/**
 * "Alle Buckets" as the key finds it now (ADR-0027, Entscheidung 2): of every bucket {@code
 * ListBuckets} names, the library reads those in the connection's region that the key may list. A
 * bucket it cannot read - another region, a refused or unanswered probe - is left out with a German
 * reason when the library holds nothing from it. One it holds documents from stays in scope as
 * withheld: the run reports it unlistable and removes nothing from it. A bucket is gone only when
 * {@code ListBuckets} no longer names it and its probe answers {@code NoSuchBucket} with that code;
 * any other answer keeps it withheld. Any other answer to a listed bucket's probe (throttling, a
 * refused key, an unreachable endpoint) is the caller's exception.
 */
final class S3AllBuckets {

  static final String OTHER_ENDPOINT = "anderer Endpoint: Region oder Adressstil passen nicht";
  static final String NOT_LISTABLE = "Auflisten nicht erlaubt, s3:ListBucket fehlt";
  static final String GONE = "nicht mehr vorhanden";
  static final String INVALID_NAME = "kein gültiger Bucket-Name";
  static final String NOT_PROBED = "nicht geprüft, das Zeitlimit war erreicht";
  static final String UNCONFIRMED =
      "fehlt in der Bucket-Liste, der Endpoint bestätigt aber nicht, dass er gelöscht ist";

  /** At most this many buckets are named in one message. */
  static final int NAMED = 20;

  /**
   * @param readable the buckets the key may list now, in name order
   * @param withheld the buckets the library holds documents from but cannot read now, name to
   *     reason; they stay in scope and nothing indexed from them is removed
   * @param outside the buckets the key sees but the library leaves out, name to reason
   * @param seen how many buckets {@code ListBuckets} named
   */
  record Resolution(
      List<String> readable, Map<String, String> withheld, Map<String, String> outside, int seen) {

    Resolution {
      readable = List.copyOf(readable);
      withheld = Collections.unmodifiableMap(new LinkedHashMap<>(withheld));
      outside = Collections.unmodifiableMap(new LinkedHashMap<>(outside));
    }

    Resolution(List<String> readable, Map<String, String> outside) {
      this(readable, Map.of(), outside, readable.size() + outside.size());
    }

    /** The buckets a full sync lists: the readable and the withheld ones, in name order. */
    List<String> inScope() {
      TreeSet<String> all = new TreeSet<>(readable);
      all.addAll(withheld.keySet());
      return List.copyOf(all);
    }

    /** "„a“ (Region us-east-1), „b“ (…)" of the outside buckets. */
    String outsideNamed() {
      return named(outside);
    }

    /** {@link #outsideNamed()} of the withheld and the outside buckets together. */
    String unreadableNamed() {
      Map<String, String> all = new java.util.TreeMap<>(outside);
      all.putAll(withheld);
      return named(all);
    }
  }

  private S3AllBuckets() {}

  /** "„a“ (reason), „b“ (…)", the first {@link #NAMED} named, the rest counted. */
  static String named(Map<String, String> reasons) {
    List<String> named = new ArrayList<>();
    reasons.forEach((bucket, reason) -> named.add("„" + bucket + "“ (" + reason + ")"));
    int shown = Math.min(named.size(), NAMED);
    return String.join(", ", named.subList(0, shown))
        + (named.size() > shown ? " und " + (named.size() - shown) + " weitere" : "");
  }

  /** The sentence a full sync reports a withheld bucket as unlistable with. */
  static String withheldMessage(String bucket, String reason) {
    return "Der Bucket „"
        + bucket
        + "“ ist nicht lesbar ("
        + reason
        + "); was aus ihm indiziert ist, bleibt, bis er wieder lesbar ist oder die Bucket-Liste ihn"
        + " nicht mehr nennt.";
  }

  /**
   * Resolves {@code listed} against {@code region} with one listing request for at most one key per
   * bucket in it, and per bucket of {@code held} - the buckets the library holds documents from -
   * that {@code listed} no longer names. {@code timeUp} stops probing; a listed bucket not probed
   * by then counts as left out, an unlisted one as withheld.
   */
  static Resolution resolve(
      S3ObjectStore store,
      S3BucketListing.Listed listed,
      String region,
      Set<String> held,
      BooleanSupplier timeUp)
      throws S3AccessException, InterruptedException {
    List<String> readable = new ArrayList<>();
    Map<String, String> withheld = new LinkedHashMap<>();
    Map<String, String> outside = new LinkedHashMap<>();
    TreeSet<String> names = new TreeSet<>(listed.names());
    for (String name : names) {
      try {
        S3Scope.validateBucket(name);
      } catch (S3Scope.InvalidS3ScopeException e) {
        outside.put(name, INVALID_NAME);
        continue;
      }
      String bucketRegion = listed.regionOf(name);
      String reason;
      if (bucketRegion != null && !bucketRegion.equalsIgnoreCase(region)) {
        reason = "Region " + bucketRegion;
      } else if (timeUp.getAsBoolean()) {
        outside.put(name, NOT_PROBED);
        continue;
      } else {
        reason = probe(store, name);
      }
      if (reason == null) {
        readable.add(name);
      } else if (held.contains(name)) {
        withheld.put(name, reason);
      } else {
        outside.put(name, reason);
      }
    }
    for (String name : new TreeSet<>(held)) {
      if (!names.contains(name)) {
        if (timeUp.getAsBoolean()) {
          withheld.put(name, NOT_PROBED);
        } else if (!confirmedGone(store, name)) {
          withheld.put(name, UNCONFIRMED);
        }
      }
    }
    return new Resolution(readable, withheld, outside, names.size());
  }

  /** Whether the store answers the probe of {@code bucket} with the code {@code NoSuchBucket}. */
  private static boolean confirmedGone(S3ObjectStore store, String bucket)
      throws InterruptedException {
    try {
      store.probeListing(bucket);
      return false;
    } catch (S3AccessException.BucketNotFound e) {
      return e.confirmed();
    } catch (S3AccessException e) {
      return false;
    }
  }

  /** {@code null} when the key may list the bucket, else why the library cannot read it. */
  private static String probe(S3ObjectStore store, String bucket)
      throws S3AccessException, InterruptedException {
    try {
      store.probeListing(bucket);
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
