package io.opaa.indexing.metadata;

import io.opaa.api.types.LibraryMetadataFieldType;
import io.opaa.indexing.Document;
import io.opaa.indexing.DocumentRepository;
import io.opaa.library.KnowledgeLibrary;
import io.opaa.llm.ActiveChatModelResolver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Step 2 of the extraction order (metadata-schema.md, "Die Reihenfolge"): one call per document to
 * the systemwide chat role for the unscharfe fields the deterministic step left empty and for the
 * freie Schlagworte, each only when the library switched it on.
 *
 * <p>A value is stored only at {@link #CONFIDENCE_THRESHOLD} or above <b>and</b> as a code of the
 * offered list; a field that already carries a row is never asked about, so no manual correction is
 * overwritten. Timeout, transport error and unusable answer all leave the field empty, count as a
 * failure and never block the ingest - there is no retry and no queue.
 */
@Service
public class ModelMetadataExtractor {

  /**
   * Fixed before the first measurement and released by the Maintainer on 05.09.2026 (ADR-0012):
   * below it a proposed value is discarded. It may be lowered after a measurement, never silently
   * raised, and every change is a commit carrying the measured distribution.
   */
  public static final double CONFIDENCE_THRESHOLD = 0.80;

  /** One call per document; longer than this the ingest does not wait for a metadata guess. */
  public static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

  /**
   * The version of the model step itself, kept apart from {@link
   * CoreMetadataExtractor#EXTRACTION_VERSION}: a corrected regular expression in step 1 must not
   * make every document of every switched-on library worth a paid model call again. Raise it when
   * the prompt, the threshold or the answer handling changes in a way that would decide a document
   * differently.
   */
  public static final int EXTRACTION_VERSION = 1;

  private static final Logger log = LoggerFactory.getLogger(ModelMetadataExtractor.class);

  private final ActiveChatModelResolver chatModelResolver;
  private final DocumentMetadataService metadataService;
  private final DocumentMetadataValueRepository valueRepository;
  private final DocumentTypeVocabularyRepository vocabularyRepository;
  private final LibraryMetadataFieldRepository fieldRepository;
  private final LibraryMetadataFieldValueRepository fieldValueRepository;
  private final DocumentKeywordRepository keywordRepository;
  private final ModelExtractionCounters counters;
  private final DocumentRepository documentRepository;
  private final Executor callExecutor;
  private final Duration callTimeout;

  @Autowired
  public ModelMetadataExtractor(
      ActiveChatModelResolver chatModelResolver,
      DocumentMetadataService metadataService,
      DocumentMetadataValueRepository valueRepository,
      DocumentTypeVocabularyRepository vocabularyRepository,
      LibraryMetadataFieldRepository fieldRepository,
      LibraryMetadataFieldValueRepository fieldValueRepository,
      DocumentKeywordRepository keywordRepository,
      ModelExtractionCounters counters,
      DocumentRepository documentRepository,
      @Qualifier("modelExtractionTaskExecutor") Executor callExecutor) {
    this(
        chatModelResolver,
        metadataService,
        valueRepository,
        vocabularyRepository,
        fieldRepository,
        fieldValueRepository,
        keywordRepository,
        counters,
        documentRepository,
        callExecutor,
        CALL_TIMEOUT);
  }

  /** The timeout is a parameter only so a test can prove the timeout path without waiting 30 s. */
  ModelMetadataExtractor(
      ActiveChatModelResolver chatModelResolver,
      DocumentMetadataService metadataService,
      DocumentMetadataValueRepository valueRepository,
      DocumentTypeVocabularyRepository vocabularyRepository,
      LibraryMetadataFieldRepository fieldRepository,
      LibraryMetadataFieldValueRepository fieldValueRepository,
      DocumentKeywordRepository keywordRepository,
      ModelExtractionCounters counters,
      DocumentRepository documentRepository,
      Executor callExecutor,
      Duration callTimeout) {
    this.chatModelResolver = chatModelResolver;
    this.metadataService = metadataService;
    this.valueRepository = valueRepository;
    this.vocabularyRepository = vocabularyRepository;
    this.fieldRepository = fieldRepository;
    this.fieldValueRepository = fieldValueRepository;
    this.keywordRepository = keywordRepository;
    this.counters = counters;
    this.documentRepository = documentRepository;
    this.callExecutor = callExecutor;
    this.callTimeout = callTimeout;
  }

  /**
   * Runs the model step for {@code document} of {@code library} over {@code text}. Returns what it
   * changed - the keywords now stored and the chunk metadata after the values were applied, or
   * {@link ModelExtractionOutcome#UNCHANGED} when nothing was asked or nothing was accepted.
   */
  public ModelExtractionOutcome extract(
      Document document, KnowledgeLibrary library, String title, String text) {
    boolean wantsValues = library.isModelExtractionEnabled();
    boolean wantsKeywords = library.isKeywordsEnabled();
    if (!wantsValues && !wantsKeywords) {
      return ModelExtractionOutcome.UNCHANGED;
    }
    List<ModelExtractionField> fields =
        wantsValues ? emptyUnsharpFieldsOf(document, library) : List.of();
    if (fields.isEmpty() && !wantsKeywords) {
      // Nothing left for the model to decide: stamped, so the Bestandslauf does not pay for a
      // second look at a document whose unscharfe fields are all filled.
      stamp(document, true, false);
      return ModelExtractionOutcome.UNCHANGED;
    }

    ModelExtractionTally tally = new ModelExtractionTally();
    ModelExtractionAnswer answer =
        askModel(ModelExtractionPrompt.build(title, text, fields, wantsKeywords), document, tally);
    ModelExtractionOutcome outcome =
        answer == null
            ? ModelExtractionOutcome.UNCHANGED
            : apply(document, library, fields, answer, wantsKeywords, tally);
    counters.record(library.getId(), document.getId(), tally);
    // One mark per capability: a library running with only one of them must still hand its
    // Altbestand to the Bestandslauf when the other is switched on later.
    stamp(document, wantsValues, wantsKeywords);
    return outcome;
  }

  /**
   * {@code null} on any failure - the one place timeout, transport error and refusal converge.
   *
   * <p>The call runs on {@link #callExecutor}, never on the common {@code ForkJoinPool}: that pool
   * is shared with everything else in the JVM, and a saturated one would make {@code get(timeout)}
   * expire on a call that was never started - a counted "failure" no model caused. The executor is
   * sized for the ingest and hands a rejected task back to the calling thread, so a call is always
   * made; only the ingest's waiting time is bounded, which is what the timeout is for.
   *
   * <p><b>A timed-out call is abandoned, not stopped.</b> {@code cancel(true)} cannot interrupt a
   * blocking HTTP read, so the abandoned call finishes on its own thread, its answer is dropped and
   * it is still billed. The field stays empty, the document is indexed regularly and the call is
   * counted as a failure - never a blocked ingest.
   */
  private ModelExtractionAnswer askModel(
      String prompt, Document document, ModelExtractionTally tally) {
    try {
      var chatClient = chatModelResolver.resolveChatClient();
      CompletableFuture<String> call =
          CompletableFuture.supplyAsync(
              () -> chatClient.prompt().user(prompt).call().content(), callExecutor);
      String raw = call.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
      ModelExtractionAnswer answer = ModelExtractionAnswer.parse(raw);
      if (answer.values().isEmpty() && answer.keywords().isEmpty()) {
        // An answer that carries neither a field nor a keyword is unusable, not an empty result:
        // counted as a failure so the Zählwerk shows a model that never answers usably.
        tally.countFailure();
        return null;
      }
      return answer;
    } catch (TimeoutException e) {
      tally.countFailure();
      log.warn(
          "Model metadata extraction for document {} exceeded {} - the field stays empty and the"
              + " document is indexed regardless",
          document.getId(),
          callTimeout);
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      tally.countFailure();
      return null;
    } catch (ExecutionException | RuntimeException e) {
      tally.countFailure();
      log.warn("Model metadata extraction failed for document {}", document.getId(), e);
      return null;
    }
  }

  private ModelExtractionOutcome apply(
      Document document,
      KnowledgeLibrary library,
      List<ModelExtractionField> fields,
      ModelExtractionAnswer answer,
      boolean wantsKeywords,
      ModelExtractionTally tally) {
    String modelId = modelIdentifier();
    List<DerivedMetadataValue> accepted = new ArrayList<>();
    for (ModelExtractionField field : fields) {
      answer
          .valueOf(field.field().key())
          .ifPresent(proposed -> evaluate(field, proposed, modelId, accepted, tally));
    }
    DocumentChunkMetadata chunkMetadata = null;
    if (!accepted.isEmpty()) {
      chunkMetadata = metadataService.applyDerivedValues(document, accepted);
    }
    List<String> keywords = List.of();
    if (wantsKeywords) {
      List<String> before = storedKeywordsOf(document);
      keywords = storeKeywords(document, library, answer.keywords(), modelId, tally);
      if (!before.equals(keywords)) {
        // A keyword is a segment of the Kontextpraefix: the caller's chunk metadata is re-read (the
        // ingest writes its chunks with it) and the stored Abdruck compared against the new one,
        // which hands an already indexed document to the Nachlauf.
        chunkMetadata = metadataService.chunkMetadataFor(document);
        metadataService.markContextPrefixStale(document.getId());
      }
    }
    return new ModelExtractionOutcome(keywords, chunkMetadata);
  }

  /**
   * The three-way decision of the specification: accepted, below the threshold, or off-vocabulary.
   */
  private void evaluate(
      ModelExtractionField field,
      ModelExtractionAnswer.ProposedValue proposed,
      String modelId,
      List<DerivedMetadataValue> accepted,
      ModelExtractionTally tally) {
    if (proposed.value() == null) {
      return;
    }
    var option = field.optionOf(proposed.value());
    if (option.isEmpty()) {
      tally.countRejection(
          field.field().key(),
          proposed.value(),
          proposed.confidence(),
          ModelExtractionTally.Reason.OUTSIDE_VOCABULARY);
      return;
    }
    if (proposed.confidence() == null || proposed.confidence() < CONFIDENCE_THRESHOLD) {
      tally.countRejection(
          field.field().key(),
          proposed.value(),
          proposed.confidence(),
          ModelExtractionTally.Reason.BELOW_THRESHOLD);
      return;
    }
    accepted.add(
        new DerivedMetadataValue(
            field.field(),
            option.get().code(),
            option.get().libraryValueId(),
            modelId,
            proposed.confidence()));
    tally.countAccepted();
  }

  /**
   * Replaces the document's keywords with what the model proposed, capped at {@link
   * DocumentKeyword#MAX_KEYWORDS_PER_DOCUMENT} entries of at most {@link
   * DocumentKeyword#MAX_KEYWORD_LENGTH} characters and deduplicated case-insensitively. A keyword
   * over the length limit is dropped, not truncated: half a word helps neither index.
   */
  /** The keywords currently stored, in the order {@link #storeKeywords} would produce them. */
  private List<String> storedKeywordsOf(Document document) {
    return keywordRepository.findByDocumentIdOrderByKeywordAsc(document.getId()).stream()
        .map(DocumentKeyword::getKeyword)
        .sorted()
        .toList();
  }

  private List<String> storeKeywords(
      Document document,
      KnowledgeLibrary library,
      List<String> proposed,
      String modelId,
      ModelExtractionTally tally) {
    Set<String> seen = new HashSet<>();
    List<String> keywords = new ArrayList<>();
    for (String candidate : proposed) {
      String keyword = candidate.trim();
      if (keyword.isEmpty() || keyword.length() > DocumentKeyword.MAX_KEYWORD_LENGTH) {
        continue;
      }
      if (!seen.add(keyword.toLowerCase(Locale.ROOT))) {
        continue;
      }
      keywords.add(keyword);
      if (keywords.size() == DocumentKeyword.MAX_KEYWORDS_PER_DOCUMENT) {
        break;
      }
    }
    keywordRepository.deleteByDocumentId(document.getId());
    keywords = keywords.stream().sorted().toList();
    for (String keyword : keywords) {
      keywordRepository.save(
          new DocumentKeyword(
              document.getId(), library.getId(), keyword, modelId, EXTRACTION_VERSION));
    }
    tally.countKeywords(keywords.size());
    return keywords;
  }

  /**
   * The unscharfe fields of {@code library} that {@code document} carries no row for: the Kernfeld
   * Dokumentart and the library's own SELECT fields. Titel, Datum/Stand and a PATTERN field are
   * deterministic or nothing and are never asked about (metadata-schema.md, Schritt 2).
   */
  private List<ModelExtractionField> emptyUnsharpFieldsOf(
      Document document, KnowledgeLibrary library) {
    Set<String> filled = new LinkedHashSet<>();
    for (DocumentMetadataValue value : valueRepository.findByDocumentId(document.getId())) {
      filled.add(value.getFieldKey());
    }
    List<ModelExtractionField> fields = new ArrayList<>();
    if (!filled.contains(CoreMetadataField.DOCUMENT_TYPE.key())) {
      List<ModelExtractionField.Option> options = new ArrayList<>();
      for (DocumentTypeVocabularyEntry entry :
          vocabularyRepository.findAllByOrderBySortOrderAsc()) {
        options.add(new ModelExtractionField.Option(entry.getCode(), entry.getLabel(), null));
      }
      if (!options.isEmpty()) {
        fields.add(
            new ModelExtractionField(
                MetadataFieldRef.of(CoreMetadataField.DOCUMENT_TYPE), List.copyOf(options)));
      }
    }
    List<LibraryMetadataField> libraryFields =
        fieldRepository.findByLibraryIdOrderBySortOrderAscFieldKeyAsc(library.getId()).stream()
            .filter(field -> field.getType() == LibraryMetadataFieldType.SELECT)
            .filter(field -> !filled.contains(field.documentFieldKey()))
            .toList();
    if (libraryFields.isEmpty()) {
      return List.copyOf(fields);
    }
    Map<UUID, List<ModelExtractionField.Option>> optionsByField = new LinkedHashMap<>();
    for (LibraryMetadataFieldValue value :
        fieldValueRepository.findByFieldIdInOrderBySortOrderAscCodeAsc(
            libraryFields.stream().map(LibraryMetadataField::getId).toList())) {
      optionsByField
          .computeIfAbsent(value.getFieldId(), id -> new ArrayList<>())
          .add(new ModelExtractionField.Option(value.getCode(), value.getLabel(), value.getId()));
    }
    for (LibraryMetadataField field : libraryFields) {
      List<ModelExtractionField.Option> options = optionsByField.get(field.getId());
      if (options != null && !options.isEmpty()) {
        fields.add(new ModelExtractionField(MetadataFieldRef.of(field), List.copyOf(options)));
      }
    }
    return List.copyOf(fields);
  }

  private String modelIdentifier() {
    try {
      return chatModelResolver.resolveDescription().modelIdentifier();
    } catch (RuntimeException e) {
      return null;
    }
  }

  private void stamp(Document document, boolean values, boolean keywords) {
    if (values) {
      documentRepository.updateModelExtractionVersion(document.getId(), EXTRACTION_VERSION);
    }
    if (keywords) {
      documentRepository.updateKeywordExtractionVersion(document.getId(), EXTRACTION_VERSION);
    }
  }
}
