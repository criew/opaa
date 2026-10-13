package io.opaa.indexing.chunk;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.embedding.EmbeddingResponseMetadata;

/**
 * An embedding endpoint with an input limit, counting one token per UTF-8 byte. It behaves like one
 * of the servers OPAA meets: cutting silently and counting what it kept (Ollama's OpenAI-compatible
 * route), refusing an input beyond the limit (Ollama's own route, OpenAI, TEI), or cutting silently
 * without any token count. Records every request's inputs.
 */
public final class ScriptedEmbeddingEndpoint implements EmbeddingModel {

  private enum Behaviour {
    SILENT_CUT,
    REFUSE,
    SILENT_CUT_WITHOUT_USAGE
  }

  private final int limit;
  private final Behaviour behaviour;
  private final List<List<String>> requests = new ArrayList<>();

  private ScriptedEmbeddingEndpoint(int limit, Behaviour behaviour) {
    this.limit = limit;
    this.behaviour = behaviour;
  }

  /** Cuts an input to {@code limit} tokens and reports the tokens it kept. */
  public static ScriptedEmbeddingEndpoint silentlyCutting(int limit) {
    return new ScriptedEmbeddingEndpoint(limit, Behaviour.SILENT_CUT);
  }

  /** Refuses a request with an input beyond {@code limit} tokens, as Ollama's own route does. */
  public static ScriptedEmbeddingEndpoint refusing(int limit) {
    return new ScriptedEmbeddingEndpoint(limit, Behaviour.REFUSE);
  }

  /** Cuts an input to {@code limit} tokens and reports no token count at all. */
  public static ScriptedEmbeddingEndpoint silentlyCuttingWithoutUsage(int limit) {
    return new ScriptedEmbeddingEndpoint(limit, Behaviour.SILENT_CUT_WITHOUT_USAGE);
  }

  /** The tokens this endpoint counts for {@code input}, before any cut. */
  public static int tokensOf(String input) {
    return input.getBytes(StandardCharsets.UTF_8).length;
  }

  /** The inputs of every request so far, in order. */
  public List<List<String>> requests() {
    return requests;
  }

  @Override
  public EmbeddingResponse call(EmbeddingRequest request) {
    List<String> inputs = request.getInstructions();
    requests.add(List.copyOf(inputs));
    int prompt = 0;
    List<Embedding> embeddings = new ArrayList<>();
    for (int i = 0; i < inputs.size(); i++) {
      int tokens = tokensOf(inputs.get(i));
      if (tokens > limit && behaviour == Behaviour.REFUSE) {
        throw new IllegalStateException(
            "400 Bad Request: {\"error\":\"the input length exceeds the context length\"}");
      }
      prompt += Math.min(tokens, limit);
      embeddings.add(new Embedding(new float[] {Math.min(tokens, limit), i}, i));
    }
    if (behaviour == Behaviour.SILENT_CUT_WITHOUT_USAGE) {
      return new EmbeddingResponse(embeddings);
    }
    return new EmbeddingResponse(
        embeddings, new EmbeddingResponseMetadata("scripted", new DefaultUsage(prompt, 0, prompt)));
  }

  @Override
  public float[] embed(Document document) {
    return call(new EmbeddingRequest(
            List.of(document.getFormattedContent(MetadataMode.EMBED)), null))
        .getResult()
        .getOutput();
  }

  @Override
  public String getEmbeddingContent(Document document) {
    return document.getFormattedContent(MetadataMode.EMBED);
  }
}
