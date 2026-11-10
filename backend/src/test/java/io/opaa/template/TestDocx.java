package io.opaa.template;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;

/** Builds small Word documents for tests, one paragraph per line, each line in one run. */
public final class TestDocx {

  private TestDocx() {}

  public static byte[] withParagraphs(String... lines) {
    try (XWPFDocument document = new XWPFDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      for (String line : lines) {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.createRun().setText(line);
      }
      document.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A document with one paragraph holding a complex field of {@code instruction}. */
  public static byte[] withFieldCode(String instruction) {
    try (XWPFDocument document = new XWPFDocument();
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      document.createParagraph().createRun().getCTR().addNewInstrText().setStringValue(instruction);
      document.write(out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A template upload of {@code content} named {@code fileName}. */
  public static DocumentTemplateUpload upload(String fileName, byte[] content) {
    return new DocumentTemplateUpload() {
      @Override
      public String fileName() {
        return fileName;
      }

      @Override
      public long sizeBytes() {
        return content.length;
      }

      @Override
      public java.io.InputStream open() {
        return new java.io.ByteArrayInputStream(content);
      }
    };
  }
}
