package io.opaa.architecture;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitCallTarget;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Four security invariants over compiled classes. Each rule answers what breaks it now, keyed by
 * top-level class, handler operation or code unit, with the first offending call as value. An
 * allowlist names justified exceptions with their reason: {@link #unlisted} answers what is neither
 * a building block nor allowed, {@link #stale} the entries that no longer break the rule.
 */
final class SecurityInvariants {

  /**
   * The members that open a connection to an address: a request sent, a stream or socket opened.
   * Keyed by owner type, subtypes included, with the methods that open one.
   */
  static final Map<String, Set<String>> CONNECTING_MEMBERS =
      Map.ofEntries(
          Map.entry("java.net.http.HttpClient", Set.of("send", "sendAsync")),
          Map.entry("java.net.http.WebSocket$Builder", Set.of("buildAsync")),
          Map.entry("java.net.URL", Set.of("openConnection", "openStream", "getContent")),
          Map.entry("java.net.Socket", Set.of("connect")),
          Map.entry("java.nio.channels.SocketChannel", Set.of("open", "connect")),
          Map.entry("javax.net.SocketFactory", Set.of("createSocket")),
          Map.entry(
              "org.springframework.web.client.RestOperations",
              Set.of(
                  "getForObject",
                  "getForEntity",
                  "headForHeaders",
                  "postForLocation",
                  "postForObject",
                  "postForEntity",
                  "put",
                  "patchForObject",
                  "delete",
                  "optionsForAllow",
                  "exchange",
                  "execute")),
          Map.entry(
              "org.springframework.web.client.RestClient$RequestHeadersSpec",
              Set.of("retrieve", "exchange", "exchangeForRequiredValue")),
          Map.entry(
              "org.springframework.web.reactive.function.client.WebClient$RequestHeadersSpec",
              Set.of("retrieve", "exchange", "exchangeToMono", "exchangeToFlux")),
          Map.entry(
              "org.springframework.http.client.ClientHttpRequestFactory", Set.of("createRequest")),
          Map.entry(
              "org.springframework.security.oauth2.jwt.NimbusJwtDecoder", Set.of("withJwkSetUri")),
          Map.entry("org.jsoup.Jsoup", Set.of("connect")),
          Map.entry("okhttp3.Call$Factory", Set.of("newCall")),
          Map.entry("org.apache.hc.client5.http.classic.HttpClient", Set.of("execute")),
          Map.entry("org.apache.hc.client5.http.async.HttpAsyncClient", Set.of("execute")),
          Map.entry("com.hierynomus.smbj.SMBClient", Set.of("connect")));

  /**
   * The building blocks that check the target before every request and every redirect hop
   * (ADR-0063); they connect without an allowlist entry.
   */
  static final Set<String> TARGET_CHECKING_BLOCKS =
      Set.of(
          "io.opaa.sourceaccess.RedirectFollowingFetcher",
          "io.opaa.sourceaccess.BoundedDownloader");

  /** The members that turn a stored ciphertext into the value it protects. */
  static final Map<String, Set<String>> DECRYPTING_MEMBERS =
      Map.of(
          "io.opaa.security.SettingsEncryptor", Set.of("decrypt"),
          "io.opaa.security.CredentialsEncryptor", Set.of("decrypt"),
          "io.opaa.security.EncryptedCredentials", Set.of("read"),
          "io.opaa.security.AesGcmCipher", Set.of("open"));

  /**
   * The building blocks that hand a stored secret only to the target it was stored for: a model key
   * only to its model's origin, a connection secret only under its {@code SecretTarget#key}.
   */
  static final Set<String> SECRET_BINDING_BLOCKS =
      Set.of("io.opaa.llm.ModelEndpointAccess", "io.opaa.connection.token.ConnectionSecrets");

  /** The package of the encryption itself; a call inside it is the mechanism, not a use. */
  static final String SECRET_MECHANISM_PACKAGE = "io.opaa.security";

  /** Annotations that check a right before the handler runs, on the method or its class. */
  static final Set<String> AUTHORIZATION_ANNOTATIONS =
      Set.of(
          "org.springframework.security.access.prepost.PreAuthorize",
          "org.springframework.security.access.prepost.PostAuthorize",
          "org.springframework.security.access.annotation.Secured",
          "jakarta.annotation.security.RolesAllowed");

  /**
   * A handler that takes its caller through one of these parameters hands the decision to the
   * domain, which checks that caller's rights.
   */
  static final Set<String> CALLER_ANNOTATIONS =
      Set.of(
          "io.opaa.auth.Caller",
          "org.springframework.security.core.annotation.AuthenticationPrincipal");

  static final Set<String> CALLER_TYPES =
      Set.of(
          "io.opaa.auth.CurrentUser",
          "org.springframework.security.core.Authentication",
          "java.security.Principal",
          "org.springframework.security.oauth2.jwt.Jwt");

  /** Members that compile their regular expression on every call. */
  static final Map<String, Set<String>> PER_CALL_REGEX_MEMBERS =
      Map.of(
          "java.lang.String", Set.of("matches", "replaceAll", "replaceFirst", "split"),
          "java.util.regex.Pattern", Set.of("matches"));

  private static final String CONTROLLER = "org.springframework.stereotype.Controller";

  private static final Pattern PATH_VARIABLE_REGEX = Pattern.compile("\\{([^}:]+):[^}]*}");

  private SecurityInvariants() {}

  /** Top-level classes that open a connection, outside {@link #TARGET_CHECKING_BLOCKS}. */
  static Map<String, String> connectingClasses(JavaClasses classes) {
    Map<String, String> callers = callers(classes, CONNECTING_MEMBERS);
    callers.keySet().removeAll(TARGET_CHECKING_BLOCKS);
    return callers;
  }

  /**
   * Top-level classes that decrypt a stored value, outside {@link #SECRET_MECHANISM_PACKAGE} and
   * {@link #SECRET_BINDING_BLOCKS}.
   */
  static Map<String, String> decryptingClasses(JavaClasses classes) {
    Map<String, String> callers = callers(classes, DECRYPTING_MEMBERS);
    callers.keySet().removeIf(top -> top.startsWith(SECRET_MECHANISM_PACKAGE + "."));
    callers.keySet().removeAll(SECRET_BINDING_BLOCKS);
    return callers;
  }

  /**
   * Handlers of the controllers in {@code classes} without an authorization check, keyed {@code
   * "<METHOD> <path template>"} like the specification ({@code ANY} for a mapping without method).
   * A handler has one when it or its class carries one of {@link #AUTHORIZATION_ANNOTATIONS}, or it
   * takes its caller through one of {@link #CALLER_ANNOTATIONS} or {@link #CALLER_TYPES}.
   */
  static Map<String, String> handlersWithoutCheck(JavaClasses classes) {
    Map<String, String> found = new TreeMap<>();
    for (JavaClass type : classes) {
      if (type.isInterface() || !type.isMetaAnnotatedWith(CONTROLLER)) {
        continue;
      }
      for (JavaMethod method : type.getMethods()) {
        if (method.isMetaAnnotatedWith(RequestMapping.class) && !hasCheck(type, method)) {
          for (String operation : operationsOf(method)) {
            found.put(operation, type.getName() + "#" + method.getName());
          }
        }
      }
    }
    return found;
  }

  /**
   * Code units below {@code rootPackage} that compile a regular expression per call: {@code
   * Pattern.compile} outside a static initializer or constructor, or one of {@link
   * #PER_CALL_REGEX_MEMBERS}. Keyed {@code <class>#<code unit>}.
   */
  static Map<String, String> perCallRegex(JavaClasses classes, String rootPackage) {
    Map<String, String> found = new TreeMap<>();
    for (JavaClass type : classes) {
      if (!type.getPackageName().equals(rootPackage)
          && !type.getPackageName().startsWith(rootPackage + ".")) {
        continue;
      }
      for (JavaCodeUnit unit : type.getCodeUnits()) {
        for (JavaCall<?> call : unit.getCallsFromSelf()) {
          CodeUnitCallTarget target = call.getTarget();
          String owner = target.getOwner().getName();
          boolean compiles =
              owner.equals("java.util.regex.Pattern")
                  && target.getName().equals("compile")
                  && !unit.getName().equals("<clinit>")
                  && !unit.isConstructor();
          if (compiles
              || PER_CALL_REGEX_MEMBERS.getOrDefault(owner, Set.of()).contains(target.getName())) {
            found.putIfAbsent(type.getName() + "#" + unit.getName(), describe(call));
          }
        }
      }
    }
    return found;
  }

  /** Lines for the entries of {@code current} that {@code allowed} does not name. */
  static Set<String> unlisted(Map<String, String> current, Map<String, String> allowed) {
    Set<String> unlisted = new TreeSet<>();
    current.forEach(
        (key, call) -> {
          if (!allowed.containsKey(key)) {
            unlisted.add(key + ": " + call);
          }
        });
    return unlisted;
  }

  /** Keys of {@code allowed} that {@code current}, the keys breaking the rule now, lacks. */
  static Set<String> stale(Collection<String> allowed, Collection<String> current) {
    Set<String> stale = new TreeSet<>(allowed);
    stale.removeAll(current);
    return stale;
  }

  private static Map<String, String> callers(
      JavaClasses classes, Map<String, Set<String>> members) {
    Map<String, String> callers = new TreeMap<>();
    for (JavaClass type : classes) {
      for (JavaCall<?> call : type.getCodeUnitCallsFromSelf()) {
        if (calls(call.getTarget(), members)) {
          callers.putIfAbsent(topLevel(type).getName(), describe(call));
        }
      }
    }
    return callers;
  }

  private static String describe(JavaCall<?> call) {
    CodeUnitCallTarget target = call.getTarget();
    return "calls "
        + target.getOwner().getName()
        + "#"
        + target.getName()
        + " in "
        + call.getOrigin().getFullName()
        + " (line "
        + call.getLineNumber()
        + ")";
  }

  private static boolean calls(CodeUnitCallTarget target, Map<String, Set<String>> members) {
    JavaClass owner = target.getOwner();
    return members.entrySet().stream()
        .anyMatch(
            entry ->
                owner.isAssignableTo(entry.getKey())
                    && entry.getValue().contains(target.getName()));
  }

  private static JavaClass topLevel(JavaClass type) {
    JavaClass top = type;
    while (top.getEnclosingClass().isPresent()) {
      top = top.getEnclosingClass().get();
    }
    return top;
  }

  private static boolean hasCheck(JavaClass type, JavaMethod method) {
    for (String annotation : AUTHORIZATION_ANNOTATIONS) {
      if (method.isMetaAnnotatedWith(annotation) || type.isMetaAnnotatedWith(annotation)) {
        return true;
      }
    }
    for (JavaParameter parameter : method.getParameters()) {
      if (CALLER_TYPES.contains(parameter.getRawType().getName())
          || CALLER_ANNOTATIONS.stream().anyMatch(parameter::isMetaAnnotatedWith)) {
        return true;
      }
    }
    return false;
  }

  /** {@code "<METHOD> <path template>"} for every method and path the handler is mapped to. */
  private static List<String> operationsOf(JavaMethod method) {
    Method reflected = method.reflect();
    RequestMapping mapping =
        AnnotatedElementUtils.findMergedAnnotation(reflected, RequestMapping.class);
    RequestMapping onType =
        AnnotatedElementUtils.findMergedAnnotation(
            reflected.getDeclaringClass(), RequestMapping.class);
    List<String> prefixes =
        onType == null || onType.path().length == 0 ? List.of("") : List.of(onType.path());
    List<String> paths = mapping.path().length == 0 ? List.of("") : List.of(mapping.path());
    List<String> methods =
        mapping.method().length == 0
            ? List.of("ANY")
            : Arrays.stream(mapping.method()).map(RequestMethod::name).toList();
    List<String> operations = new ArrayList<>();
    for (String prefix : prefixes) {
      for (String path : paths) {
        String template = PATH_VARIABLE_REGEX.matcher(prefix + path).replaceAll("{$1}");
        for (String requestMethod : methods) {
          operations.add(requestMethod + " " + (template.isEmpty() ? "/" : template));
        }
      }
    }
    return operations;
  }
}
