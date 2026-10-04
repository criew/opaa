package io.opaa.migration;

import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Where {@code uk_documents_library_path} ends: a B-tree entry holds 2676 bytes of a path the
 * database cannot compress, so a {@code file_path} of the full 2000 characters fits only while it
 * stays near one byte per character - 1338 random two-byte or 892 random three-byte characters at
 * most. The paths are random so that compression cannot shift the boundary.
 */
class DocumentFilePathIndexLimitTest extends AbstractBaselineTest {

  private static final int FULL_WIDTH = 2000;

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.files();
  }

  @Test
  void aPathOfTheFullWidthInOneByteCharactersFits() throws Exception {
    UUID library = insertLibrary("SMB", "smb://server/freigabe");

    insertDocument(library, random(FULL_WIDTH, 'a', 26));
  }

  @Test
  void aPathOfTheFullWidthInTwoByteCharactersExceedsTheIndexEntry() throws Exception {
    UUID library = insertLibrary("SMB", "smb://server/freigabe");

    assertRejected(insertSql(library, random(FULL_WIDTH, 'А', 64)), "index row size");
  }

  @Test
  void aPathOfTheFullWidthInThreeByteCharactersExceedsTheIndexEntry() throws Exception {
    UUID library = insertLibrary("SMB", "smb://server/freigabe");

    assertRejected(insertSql(library, random(FULL_WIDTH, '一', 2000)), "index row size");
  }

  @Test
  void theIndexEntryEndsAt2676BytesOfIncompressibleText() throws Exception {
    UUID library = insertLibrary("SMB", "smb://server/freigabe");

    execute(insertSql(library, random(1338, 'А', 64)));
    assertRejected(insertSql(library, random(1339, 'Б', 64)), "index row size");
  }

  private String insertSql(UUID library, String filePath) {
    return "INSERT INTO documents (id, file_name, file_path, status, source_type, library_id,"
        + " organization_id) VALUES (gen_random_uuid(), 'x.txt', '"
        + filePath
        + "', 'INDEXED', 'SMB', '"
        + library
        + "', '"
        + SEEDED_ORGANIZATION_ID
        + "')";
  }

  /** {@code length} characters drawn from {@code range} code points above {@code first}. */
  private static String random(int length, char first, int range) {
    Random random = new Random(2201);
    StringBuilder path = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      path.append((char) (first + random.nextInt(range)));
    }
    return path.toString();
  }
}
