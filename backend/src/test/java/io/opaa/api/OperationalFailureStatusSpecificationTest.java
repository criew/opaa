package io.opaa.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opaa.common.ServiceUnavailableException;
import io.opaa.library.SourceCredentialsConverter;
import io.opaa.library.UploadProperties;
import io.opaa.query.answer.AnswerGenerationService;
import io.opaa.query.retrieval.RetrievalPipeline;
import io.opaa.security.CredentialsEncryptionKeyMissingException;
import io.opaa.security.CredentialsEncryptionProperties;
import io.opaa.security.CredentialsEncryptor;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

/**
 * The other half of the status-code rule {@link TransportStatusCodeSpecificationTest} holds: {@code
 * 502} and {@code 503} are <em>not</em> transport codes the infrastructure answers for every call,
 * they are answered by the few operations that themselves depend on something outside this service
 * - the AI provider one of them calls on the request thread, the object store another reads an
 * original from, the worker capacity a run is handed to, the encryption key a stored credential
 * needs. This test is what keeps that set from drifting in either direction.
 *
 * <p>The premises are read off the production side rather than listed: the two statuses are taken
 * from the very {@link GlobalExceptionHandler} branches that answer them, the operations that may
 * declare the AI pair are derived from the constructor-injected object graph of the
 * {@code @RestController}s - whichever of them can reach the retrieval pipeline or the answer
 * generation -, and the write/read asymmetry that decides which library operation declares the
 * missing encryption key is asserted against the production {@link SourceCredentialsConverter}
 * itself. No Spring context.
 *
 * <p><b>What this test cannot see, deliberately named rather than hidden.</b> The AI half is
 * derived at <em>controller</em> granularity: an object graph says which controller class runs the
 * model, never which of its handler methods does. A controller that serves both a model-calling and
 * a plain operation therefore passes as long as <em>one</em> of them declares the pair, and moving
 * the declaration between them would go unnoticed. Two further blind spots follow from the same
 * limit: a model call made through {@code ActiveChatModelResolver} directly rather than through the
 * pipeline is not a seam here (the asynchronous title, note and indexing calls hold that resolver
 * as an ordinary field and answer nothing to a caller, so treating it as one would demand the pair
 * at every chat operation), and an operation that stores a source credential without answering with
 * the stored library escapes the credential half. A guard for those would need a call graph, which
 * cannot tell a caught exception or an executor hand-off from a failure that reaches the caller.
 */
class OperationalFailureStatusSpecificationTest {

  /**
   * The production types that <em>are</em> a caller-facing model round trip: the retrieval pipeline
   * embeds every search query, the answer generation makes the chat call. A controller whose object
   * graph holds one of them can answer for the provider.
   */
  private static final Set<Class<?>> MODEL_SEAMS =
      Set.of(RetrievalPipeline.class, AnswerGenerationService.class);

  private static final String BAD_GATEWAY_BODY = "#/components/responses/BadGateway";
  private static final String SERVICE_UNAVAILABLE_BODY =
      "#/components/responses/ServiceUnavailable";

  private static final Set<String> HTTP_METHODS =
      Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

  private static Map<String, Object> spec;

  @BeforeAll
  static void loadSpec() throws Exception {
    try (InputStream in =
        OperationalFailureStatusSpecificationTest.class.getResourceAsStream(
            "/openapi/opaa-api.yaml")) {
      spec = new Yaml().load(in);
    }
  }

  /**
   * The two statuses the specification declares are the two the handlers actually answer - read off
   * the production branches, so a handler remapped to something else takes this test with it
   * instead of leaving the specification quietly wrong.
   */
  @Test
  void theTwoStatusesAreTheOnesTheProductionHandlersAnswer() {
    GlobalExceptionHandler handler =
        new GlobalExceptionHandler(new UploadProperties(null, null, 52_428_800L, null, 0, 0));

    assertThat(
            handler
                .handleTransientAiException(new TransientAiException("x"))
                .getStatusCode()
                .value())
        .as("a transient AI failure")
        .isEqualTo(503);
    assertThat(
            handler
                .handleNonTransientAiException(new NonTransientAiException("x"))
                .getStatusCode()
                .value())
        .as("a non-transient AI failure")
        .isEqualTo(502);
    assertThat(
            handler
                .handleServiceUnavailableException(new ServiceUnavailableException("x"))
                .getStatusCode()
                .value())
        .as("an unavailable dependency of the operation")
        .isEqualTo(503);
    assertThat(
            handler
                .handleCredentialsEncryptionKeyMissingException(
                    new CredentialsEncryptionKeyMissingException("x"))
                .getStatusCode()
                .value())
        .as("a missing credentials encryption key")
        .isEqualTo(503);
  }

  /**
   * Both statuses carry the same {@code ErrorResponse} every other error answer carries, so both
   * are declared through the one shared response rather than through a body written out again per
   * operation.
   */
  @Test
  void everyDeclarationUsesTheOneSharedErrorBody() {
    forEachOperation(
        (path, method, operation) -> {
          Map<String, Object> responses = map(operation, "responses");
          assertSharedBody(operation, responses.get("502"), BAD_GATEWAY_BODY);
          assertSharedBody(operation, responses.get("503"), SERVICE_UNAVAILABLE_BODY);
        });
  }

  /**
   * The handler pair above splits one provider failure into a transient and a non-transient half,
   * so an operation that can answer the {@code 502} can always answer the {@code 503} too - a
   * {@code 502} on its own would mean a caller has to handle the rarer half and not the common one.
   */
  @Test
  void a502IsNeverDeclaredWithoutTheTransientHalfOfTheSameFailure() {
    assertThat(operationsDeclaring("503"))
        .as("operations declaring 502 but not 503")
        .containsAll(operationsDeclaring("502"));
  }

  /**
   * The AI pair sits at the controllers that run the model on the request thread and at no others,
   * derived from the production object graph: a controller wired to the retrieval pipeline or the
   * answer generation must declare it, and an operation declaring it must belong to such a
   * controller. See the class Javadoc for the granularity this cannot reach.
   */
  @Test
  void a502IsDeclaredAtTheControllersThatRunTheModelOnTheRequestThread() {
    Set<Class<?>> runningTheModel = controllersReaching(MODEL_SEAMS);
    assertThat(runningTheModel)
        .as("no controller reaches the model any more - the derivation lost its premise")
        .isNotEmpty();

    Set<String> declaring502 = operationsDeclaring("502");
    Set<String> servedByThem = new TreeSet<>();
    for (Class<?> controller : runningTheModel) {
      Set<String> operations = operationsOf(controller);
      assertThat(operations)
          .as("%s serves no operation of the specification", controller.getSimpleName())
          .isNotEmpty();
      assertThat(operations)
          .as("%s runs the model but declares 502 at none of its operations", controller.getName())
          .containsAnyElementsOf(declaring502);
      servedByThem.addAll(operations);
    }

    assertThat(declaring502)
        .as("operations declaring 502 without a controller that runs the model")
        .isSubsetOf(servedByThem);
  }

  /**
   * The premise the credential half rests on, asserted against the production converter: without a
   * usable key a credential on its way <em>into</em> the database is refused, while one on its way
   * out is reported as absent. Only an operation that writes one can therefore answer {@code 503}.
   */
  @Test
  void aCredentialIsRefusedOnWriteWithoutTheKeyAndToleratedOnRead() {
    SourceCredentialsConverter converter =
        new SourceCredentialsConverter(
            new CredentialsEncryptor(new CredentialsEncryptionProperties(null)));

    assertThatThrownBy(() -> converter.convertToDatabaseColumn("user:secret"))
        .as("a credential written without a key")
        .isInstanceOf(CredentialsEncryptionKeyMissingException.class);
    assertThat(converter.convertToEntityAttribute("enc:v1:Zm9vYmFy"))
        .as("a credential read without a key")
        .isNull();
  }

  /**
   * Of the operations whose request carries a {@code sourceCredentials}, the ones that answer with
   * the stored library have persisted it and declare the missing key; the ones that only probe with
   * it have written nothing and must not.
   */
  @Test
  void everyOperationThatStoresASourceCredentialDeclaresTheMissingKey() {
    String storedLibrary = successSchemaOf("getLibrary");
    Set<String> storing = new TreeSet<>();
    Set<String> probing = new TreeSet<>();
    forEachOperation(
        (path, method, operation) -> {
          if (!requestCarries(operation, "sourceCredentials")) {
            return;
          }
          String operationId = (String) operation.get("operationId");
          if (storedLibrary.equals(successSchemaOf(operation))) {
            storing.add(operationId);
          } else {
            probing.add(operationId);
          }
        });

    assertThat(storing).as("no operation stores a source credential any more").isNotEmpty();
    assertThat(probing).as("no operation probes with a source credential any more").isNotEmpty();
    assertThat(operationsDeclaring("503"))
        .as("storing a credential: %s, probing with one: %s", storing, probing)
        .containsAll(storing)
        .doesNotContainAnyElementsOf(probing);
  }

  private static void assertSharedBody(
      Map<String, Object> operation, Object declared, String expectedRef) {
    if (declared == null) {
      return;
    }
    assertThat(declared)
        .as("%s declares its own body instead of the shared one", operation.get("operationId"))
        .isInstanceOf(Map.class);
    assertThat(((Map<?, ?>) declared).get("$ref"))
        .as("the body %s declares", operation.get("operationId"))
        .isEqualTo(expectedRef);
  }

  /**
   * Every {@code @RestController} whose constructor-injected object graph holds one of {@code
   * seams}. A fixpoint over the graph rather than a depth-first walk, so a cycle in it (two
   * services holding each other) cannot silently cut a branch short.
   */
  private static Set<Class<?>> controllersReaching(Set<Class<?>> seams) {
    Set<Class<?>> controllers = restControllers();
    Map<Class<?>, Set<Class<?>>> collaborators = new LinkedHashMap<>();
    Deque<Class<?>> pending = new ArrayDeque<>(controllers);
    while (!pending.isEmpty()) {
      Class<?> type = pending.poll();
      if (collaborators.containsKey(type)) {
        continue;
      }
      Set<Class<?>> targets = collaboratorsOf(type);
      collaborators.put(type, targets);
      pending.addAll(targets);
    }

    Set<Class<?>> reaching = new LinkedHashSet<>();
    boolean grew = true;
    while (grew) {
      grew = false;
      for (Map.Entry<Class<?>, Set<Class<?>>> entry : collaborators.entrySet()) {
        if (reaching.contains(entry.getKey())) {
          continue;
        }
        boolean holdsOne =
            entry.getValue().stream()
                .anyMatch(target -> seams.contains(target) || reaching.contains(target));
        if (holdsOne) {
          reaching.add(entry.getKey());
          grew = true;
        }
      }
    }
    reaching.retainAll(controllers);
    return reaching;
  }

  private static Set<Class<?>> restControllers() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
    Set<Class<?>> controllers = new LinkedHashSet<>();
    for (BeanDefinition definition : scanner.findCandidateComponents("io.opaa")) {
      controllers.add(load(definition.getBeanClassName()));
    }
    assertThat(controllers).as("no @RestController found under io.opaa").isNotEmpty();
    return controllers;
  }

  /** The project's own types {@code type} holds as fields, generic wrappers unwrapped one level. */
  private static Set<Class<?>> collaboratorsOf(Class<?> type) {
    Set<Class<?>> collaborators = new LinkedHashSet<>();
    for (Class<?> current = type; current != null && current != Object.class; ) {
      for (Field field : current.getDeclaredFields()) {
        for (Class<?> candidate : typesOf(field.getGenericType())) {
          if (candidate.getName().startsWith("io.opaa.")) {
            collaborators.add(candidate);
          }
        }
      }
      current = current.getSuperclass();
    }
    return collaborators;
  }

  private static Set<Class<?>> typesOf(Type type) {
    if (type instanceof Class<?> raw) {
      return Set.of(raw);
    }
    if (type instanceof ParameterizedType parameterized) {
      Set<Class<?>> types = new LinkedHashSet<>(typesOf(parameterized.getRawType()));
      for (Type argument : parameterized.getActualTypeArguments()) {
        types.addAll(typesOf(argument));
      }
      return types;
    }
    return Set.of();
  }

  /** The operation ids of the specification {@code controller}'s request mappings resolve to. */
  private static Set<String> operationsOf(Class<?> controller) {
    String base = firstPath(controller.getDeclaredAnnotation(RequestMapping.class));
    Set<String> operations = new TreeSet<>();
    for (Method handler : controller.getDeclaredMethods()) {
      for (Map.Entry<String, String> mapping : mappingsOf(handler).entrySet()) {
        String operationId = operationIdAt(mapping.getValue(), base + mapping.getKey());
        assertThat(operationId)
            .as(
                "%s#%s maps a path the specification does not know",
                controller.getSimpleName(), handler.getName())
            .isNotNull();
        operations.add(operationId);
      }
    }
    return operations;
  }

  /**
   * The HTTP method per mapped path suffix of {@code handler}. Read off the directly declared
   * annotations only: every shortcut is itself meta-annotated with {@code @RequestMapping}, so a
   * meta-annotation-aware lookup would count the same mapping twice.
   */
  private static Map<String, String> mappingsOf(Method handler) {
    Map<String, String> mappings = new LinkedHashMap<>();
    addMapping(mappings, handler, GetMapping.class, "get");
    addMapping(mappings, handler, PostMapping.class, "post");
    addMapping(mappings, handler, PutMapping.class, "put");
    addMapping(mappings, handler, PatchMapping.class, "patch");
    addMapping(mappings, handler, DeleteMapping.class, "delete");
    RequestMapping generic = handler.getDeclaredAnnotation(RequestMapping.class);
    if (generic != null) {
      for (RequestMethod method : generic.method()) {
        mappings.put(firstPath(generic), method.name().toLowerCase(Locale.ROOT));
      }
    }
    return mappings;
  }

  private static <A extends Annotation> void addMapping(
      Map<String, String> mappings, Method handler, Class<A> annotation, String httpMethod) {
    A found = handler.getDeclaredAnnotation(annotation);
    if (found != null) {
      mappings.put(firstPath(found), httpMethod);
    }
  }

  private static String firstPath(Annotation mapping) {
    if (mapping == null) {
      return "";
    }
    Object value = AnnotationUtils.getValue(mapping, "value");
    String[] paths = value instanceof String[] array ? array : new String[0];
    return paths.length == 0 ? "" : paths[0];
  }

  private static String operationIdAt(String httpMethod, String path) {
    Object item = map(spec, "paths").get(path);
    if (!(item instanceof Map<?, ?> pathItem)) {
      return null;
    }
    Object operation = pathItem.get(httpMethod);
    return operation instanceof Map<?, ?> found ? (String) found.get("operationId") : null;
  }

  private static Set<String> operationsDeclaring(String status) {
    Set<String> declaring = new TreeSet<>();
    forEachOperation(
        (path, method, operation) -> {
          if (map(operation, "responses").containsKey(status)) {
            declaring.add((String) operation.get("operationId"));
          }
        });
    return declaring;
  }

  /** Whether {@code operation}'s JSON request body carries {@code property}. */
  private static boolean requestCarries(Map<String, Object> operation, String property) {
    Object requestBody = operation.get("requestBody");
    if (!(requestBody instanceof Map<?, ?> body)) {
      return false;
    }
    return propertiesOf(schemaRefOf(body.get("content"))).contains(property);
  }

  private static String successSchemaOf(String operationId) {
    Set<String> schemas = new TreeSet<>();
    forEachOperation(
        (path, method, operation) -> {
          if (operationId.equals(operation.get("operationId"))) {
            String schema = successSchemaOf(operation);
            if (schema != null) {
              schemas.add(schema);
            }
          }
        });
    assertThat(schemas).as("the success schema of %s", operationId).hasSize(1);
    return schemas.iterator().next();
  }

  private static String successSchemaOf(Map<String, Object> operation) {
    for (Map.Entry<String, Object> response : map(operation, "responses").entrySet()) {
      if (!response.getKey().startsWith("2") || !(response.getValue() instanceof Map<?, ?> body)) {
        continue;
      }
      String schema = schemaRefOf(body.get("content"));
      if (schema != null) {
        return schema;
      }
    }
    return null;
  }

  /** The component schema name behind {@code content}'s JSON media type, or {@code null}. */
  private static String schemaRefOf(Object content) {
    if (!(content instanceof Map<?, ?> types)) {
      return null;
    }
    Object json = types.get("application/json");
    if (!(json instanceof Map<?, ?> mediaType)
        || !(mediaType.get("schema") instanceof Map<?, ?> schema)) {
      return null;
    }
    Object ref = schema.get("$ref");
    return ref instanceof String reference
        ? reference.substring(reference.lastIndexOf('/') + 1)
        : null;
  }

  /** The property names of a component schema, following one level of {@code allOf}. */
  private static Set<String> propertiesOf(String schemaName) {
    if (schemaName == null) {
      return Set.of();
    }
    Object schema = map(map(spec, "components"), "schemas").get(schemaName);
    if (!(schema instanceof Map<?, ?> definition)) {
      return Set.of();
    }
    Set<String> properties = new TreeSet<>();
    if (definition.get("properties") instanceof Map<?, ?> declared) {
      declared.keySet().forEach(key -> properties.add((String) key));
    }
    if (definition.get("allOf") instanceof List<?> parts) {
      for (Object part : parts) {
        if (part instanceof Map<?, ?> element && element.get("$ref") instanceof String ref) {
          properties.addAll(propertiesOf(ref.substring(ref.lastIndexOf('/') + 1)));
        }
      }
    }
    return properties;
  }

  private static Class<?> load(String className) {
    try {
      return Class.forName(
          className, false, OperationalFailureStatusSpecificationTest.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("cannot load " + className, e);
    }
  }

  private interface OperationVisitor {
    void visit(String path, String method, Map<String, Object> operation);
  }

  @SuppressWarnings("unchecked")
  private static void forEachOperation(OperationVisitor visitor) {
    map(spec, "paths")
        .forEach(
            (path, item) ->
                ((Map<String, Object>) item)
                    .forEach(
                        (method, operation) -> {
                          if (HTTP_METHODS.contains(method)) {
                            visitor.visit(path, method, (Map<String, Object>) operation);
                          }
                        }));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Map<String, Object> parent, String key) {
    Object value = parent.get(key);
    assertThat(value).as(key).isInstanceOf(Map.class);
    return (Map<String, Object>) value;
  }
}
