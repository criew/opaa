package io.opaa.permission;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The kind of asset an {@link AssetGrant} refers to, as it is stored in {@code
 * asset_grants.asset_type} (ADR-0036, Entscheidung 12).
 *
 * <p><b>Deliberately not an enum.</b> This package must not know which asset types exist - that is
 * exactly the type independence #1726 builds on: the owning business package declares its own
 * constant ({@code KnowledgeLibrary#ASSET_TYPE}), and a new asset type needs no change here and no
 * migration. The value type exists nonetheless, so an asset type cannot be confused with any other
 * string in a signature.
 *
 * @param value the stored value: upper case, digits and underscores, at most 30 characters - the
 *     width of the column.
 */
public record AssetType(String value) {

  private static final int MAX_LENGTH = 30;
  private static final Pattern ALLOWED = Pattern.compile("[A-Z][A-Z0-9_]*");

  public AssetType {
    Objects.requireNonNull(value, "value must not be null");
    if (value.length() > MAX_LENGTH || !ALLOWED.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "asset type must match "
              + ALLOWED.pattern()
              + " and be at most "
              + MAX_LENGTH
              + " chars");
    }
  }

  public static AssetType of(String value) {
    return new AssetType(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
