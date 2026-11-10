package io.opaa.template;

import java.io.IOException;
import java.io.InputStream;

/** An uploaded template file as the web layer received it. */
public interface DocumentTemplateUpload {

  String fileName();

  long sizeBytes();

  InputStream open() throws IOException;
}
