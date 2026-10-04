package io.opaa.migration;

import static org.assertj.core.api.Assertions.assertThat;

import io.opaa.indexing.filesync.FilePathLimit;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Where {@code uk_documents_library_path} ends, and that {@link FilePathLimit} stays within it: a
 * B-tree entry holds {@link FilePathLimit#MAX_BYTES} bytes of a path the database cannot compress,
 * so a {@code file_path} of the full 2000 characters fits only while it stays near one byte per
 * character. The paths are random so that compression cannot shift the boundary.
 */
class DocumentFilePathIndexLimitTest extends AbstractBaselineTest {

  @Override
  protected List<String> baseFixtureChangelogs() {
    return MasterChangelog.files();
  }

  @Test
  void aPathOfTheFullWidthInOneByteCharactersFits() throws Exception {
    UUID library = insertLibrary("SMB", "smb://server/freigabe");

    execute(insertSql(library, random(FilePathLimit.MAX_CHARACTERS, 'a', 26)));
  }

  @Test
  void aPathOfTheFullWidthInTwoByteCharactersExceedsTheIndexEntry() throws Exception {
    UUID library = insertLibrary("SMB", "smb://server/freigabe");

    assertRejected(
        insertSql(library, random(FilePathLimit.MAX_CHARACTERS, 'А', 64)), "index row size");
  }

  @Test
  void theIndexEntryEndsAtTheLimitInTwoByteCharacters() throws Exception {
    UUID library = insertLibrary("SMB", "smb://server/freigabe");

    execute(insertSql(library, random(FilePathLimit.MAX_BYTES / 2, 'А', 64)));
    assertRejected(
        insertSql(library, random(FilePathLimit.MAX_BYTES / 2 + 1, 'Б', 64)), "index row size");
  }

  @Test
  void theIndexEntryEndsAtTheLimitInThreeByteCharacters() throws Exception {
    UUID library = insertLibrary("SMB", "smb://server/freigabe");

    execute(insertSql(library, random(FilePathLimit.MAX_BYTES / 3, '一', 2000)));
    assertRejected(
        insertSql(library, random(FilePathLimit.MAX_BYTES / 3 + 1, '丁', 2000)), "index row size");
  }

  @Test
  void aPathCutToTheLimitIsStored() throws Exception {
    UUID library = insertLibrary("SMB", "smb://server/freigabe");
    String cut = FilePathLimit.cut("smb://server/freigabe/" + random(1500, '一', 2000));

    assertThat(FilePathLimit.fits(cut)).isTrue();
    assertThat(cut.getBytes(StandardCharsets.UTF_8).length)
        .isGreaterThan(FilePathLimit.MAX_BYTES - 3);
    execute(insertSql(library, cut));
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
