package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The profile requirement on {@code connector_type_policies}: it is set only together with what
 * happens to the libraries with their own address, and that stock is one of the known values.
 */
class ConnectorProfileRequirementTest extends AbstractBaselineTest {

  @Test
  void theRequirementAndTheStockAreSetTogetherAndTheStockIsKnown() throws Exception {
    assertRejected(
        insert("'S3'", "now()", "NULL"), "chk_connector_type_policies_profile_requirement");
    assertRejected(
        insert("'S3'", "NULL", "'RUNS'"), "chk_connector_type_policies_profile_requirement");
    assertRejected(
        insert("'S3'", "now()", "'PAUSED'"), "chk_connector_type_policies_own_address_stock");
    execute(insert("'S3'", "now()", "'RUNS'"));
    execute(insert("'CONFLUENCE'", "now()", "'LOCKED'"));
    execute(insert("'NEXTCLOUD'", "NULL", "NULL"));

    assertThat(countWhere("connector_type_policies", "profile_required_at IS NOT NULL"))
        .isEqualTo(2);
  }

  private static String insert(String type, String requiredAt, String stock) {
    return "INSERT INTO connector_type_policies (source_type, updated_at, profile_required_at,"
        + " own_address_stock) VALUES ("
        + type
        + ", now(), "
        + requiredAt
        + ", "
        + stock
        + ")";
  }
}
