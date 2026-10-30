package io.opaa.architecture;

import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget;
import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaCodeUnitAccess;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * Four security invariants over compiled classes. Each rule answers what breaks it now, keyed by
 * code unit ({@code <class>#<method>}, a lambda counted to the method it is written in) or handler
 * operation, with the first offending call or method reference as value. An allowlist names
 * justified exceptions with their reason: {@link #unlisted} answers what is neither a building
 * block nor allowed, {@link #stale} the entries that no longer break the rule.
 *
 * <p>The rules see OPAA's own bytecode only. A connection a library opens inside itself - the model
 * clients of Spring AI, the AWS SDK, JDBC, the JWK fetch of Nimbus - is out of their scope; so are
 * router functions, the MCP endpoint, actuator endpoints and paths a filter answers itself.
 */
final class SecurityInvariants {

  /**
   * The members that open a connection to an address: a request sent, a stream or socket opened, a
   * mail transport connected. Keyed by owner type, subtypes included, with the methods that open
   * one; {@link #connectsOnConstruction} adds the socket constructors that connect.
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
          Map.entry("com.hierynomus.smbj.SMBClient", Set.of("connect")),
          Map.entry("org.springframework.mail.MailSender", Set.of("send")),
          Map.entry(
              "org.springframework.mail.javamail.JavaMailSenderImpl", Set.of("testConnection")),
          Map.entry("jakarta.mail.Service", Set.of("connect")),
          Map.entry("jakarta.mail.Transport", Set.of("send", "sendMessage")));

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

  /**
   * The encryption itself: a decryption inside these classes is the mechanism, one layer calling
   * the next down to the key ring, not a use of the secret.
   */
  static final Set<String> SECRET_MECHANISM_CLASSES =
      Set.of(
          "io.opaa.security.SettingsEncryptor",
          "io.opaa.security.CredentialsEncryptor",
          "io.opaa.security.EncryptedCredentials",
          "io.opaa.security.EncryptionKeyring");

  /** Annotations that check a right before the handler runs, on the method or its class. */
  static final Set<String> AUTHORIZATION_ANNOTATIONS =
      Set.of(
          "org.springframework.security.access.prepost.PreAuthorize",
          "org.springframework.security.access.prepost.PostAuthorize",
          "org.springframework.security.access.annotation.Secured",
          "jakarta.annotation.security.RolesAllowed");

  /** An authorization annotation whose expression or role lets every caller in checks nothing. */
  private static final Pattern LETS_EVERYONE_IN =
      Pattern.compile("permitAll|isAnonymous|ANONYMOUS");

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

  /** The synthetic method of a lambda, named after the method it is written in. */
  private static final Pattern LAMBDA = Pattern.compile("lambda\\$(.+)\\$\\d+");

  private SecurityInvariants() {}

  /** Code units that open a connection, outside {@link #TARGET_CHECKING_BLOCKS}. */
  static Map<String, String> connectingUnits(JavaClasses classes) {
    return offenders(
        classes,
        access -> matches(access, CONNECTING_MEMBERS) || connectsOnConstruction(access),
        type -> TARGET_CHECKING_BLOCKS.contains(topLevel(type).getName()));
  }

  /**
   * Code units that decrypt a stored value, outside {@link #SECRET_MECHANISM_CLASSES} and {@link
   * #SECRET_BINDING_BLOCKS}.
   */
  static Map<String, String> decryptingUnits(JavaClasses classes) {
    return offenders(
        classes,
        access -> matches(access, DECRYPTING_MEMBERS),
        type -> {
          String top = topLevel(type).getName();
          return SECRET_MECHANISM_CLASSES.contains(top) || SECRET_BINDING_BLOCKS.contains(top);
        });
  }

  /** The classes of {@link #SECRET_MECHANISM_CLASSES} that decrypt, by their top-level name. */
  static Set<String> decryptingMechanismClasses(JavaClasses classes) {
    Set<String> found = new TreeSet<>();
    for (JavaClass type : classes) {
      String top = topLevel(type).getName();
      if (SECRET_MECHANISM_CLASSES.contains(top)
          && type.getCodeUnits().stream()
              .flatMap(SecurityInvariants::accessesOf)
              .anyMatch(access -> matches(access, DECRYPTING_MEMBERS))) {
        found.add(top);
      }
    }
    return found;
  }

  /**
   * Handlers of the controllers in {@code classes} without an authorization check, keyed {@code
   * "<METHOD> <path template>"} like the specification ({@code ANY} for a mapping without method).
   * A handler has one when it or its class carries one of {@link #AUTHORIZATION_ANNOTATIONS} that
   * does not let everyone in, or it takes its caller through one of {@link #CALLER_ANNOTATIONS} or
   * {@link #CALLER_TYPES}.
   */
  static Map<String, String> handlersWithoutCheck(JavaClasses classes) {
    Map<String, String> found = new TreeMap<>();
    for (JavaClass type : classes) {
      if (type.isInterface() || !type.isMetaAnnotatedWith(CONTROLLER)) {
        continue;
      }
      for (JavaMethod method : type.getMethods()) {
        if (method.isMetaAnnotatedWith(RequestMapping.class) && !hasCheck(method)) {
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
   * Pattern.compile} outside a static initializer, or one of {@link #PER_CALL_REGEX_MEMBERS},
   * called or referenced. A constructor counts, since it runs once per object.
   */
  static Map<String, String> perCallRegex(JavaClasses classes, String rootPackage) {
    Map<String, String> found = new TreeMap<>();
    for (JavaClass type : classes) {
      if (!type.getPackageName().equals(rootPackage)
          && !type.getPackageName().startsWith(rootPackage + ".")) {
        continue;
      }
      for (JavaCodeUnit unit : type.getCodeUnits()) {
        accessesOf(unit)
            .filter(
                access ->
                    (isMember(access, "java.util.regex.Pattern", "compile")
                            && !unit.getName().equals("<clinit>"))
                        || matches(access, PER_CALL_REGEX_MEMBERS))
            .forEach(access -> found.putIfAbsent(keyOf(unit), describe(access)));
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

  private static Map<String, String> offenders(
      JavaClasses classes, Predicate<JavaCodeUnitAccess<?>> offends, Predicate<JavaClass> exempt) {
    Map<String, String> found = new TreeMap<>();
    for (JavaClass type : classes) {
      if (exempt.test(type)) {
        continue;
      }
      for (JavaCodeUnit unit : type.getCodeUnits()) {
        accessesOf(unit)
            .filter(offends)
            .forEach(access -> found.putIfAbsent(keyOf(unit), describe(access)));
      }
    }
    return found;
  }

  /** Calls and method or constructor references of {@code unit}, in source order. */
  private static Stream<JavaCodeUnitAccess<?>> accessesOf(JavaCodeUnit unit) {
    List<JavaCodeUnitAccess<?>> accesses = new ArrayList<>(unit.getCallsFromSelf());
    accesses.addAll(unit.getCodeUnitReferencesFromSelf());
    accesses.sort(
        Comparator.<JavaCodeUnitAccess<?>>comparingInt(JavaAccess::getLineNumber)
            .thenComparing(JavaAccess::getName));
    return accesses.stream();
  }

  /** {@code <class>#<method>}; a lambda counts to the method it is written in. */
  private static String keyOf(JavaCodeUnit unit) {
    Matcher lambda = LAMBDA.matcher(unit.getName());
    String name = lambda.matches() ? lambda.group(1) : unit.getName();
    return unit.getOwner().getName() + "#" + name;
  }

  private static String describe(JavaCodeUnitAccess<?> access) {
    String kind = access instanceof JavaCall ? "calls " : "refers to ";
    return kind
        + access.getTargetOwner().getName()
        + "#"
        + access.getTarget().getName()
        + " in "
        + access.getOrigin().getFullName()
        + " (line "
        + access.getLineNumber()
        + ")";
  }

  private static boolean matches(JavaCodeUnitAccess<?> access, Map<String, Set<String>> members) {
    JavaClass owner = access.getTargetOwner();
    return members.entrySet().stream()
        .anyMatch(
            entry ->
                owner.isAssignableTo(entry.getKey())
                    && entry.getValue().contains(access.getTarget().getName()));
  }

  private static boolean isMember(JavaCodeUnitAccess<?> access, String owner, String name) {
    return access.getTargetOwner().getName().equals(owner)
        && access.getTarget().getName().equals(name);
  }

  /**
   * A socket constructor that takes an address connects at once: {@code new Socket(host, port)} and
   * its {@code SSLSocket} kin. The constructors without one ({@code Socket()}, {@code
   * Socket(Proxy)}, {@code Socket(SocketImpl)}) leave the connecting to {@code connect}.
   */
  private static boolean connectsOnConstruction(JavaCodeUnitAccess<?> access) {
    if (!access.getTarget().getName().equals("<init>")
        || !access.getTargetOwner().isAssignableTo("java.net.Socket")) {
      return false;
    }
    List<JavaClass> parameters = ((CodeUnitAccessTarget) access.getTarget()).getRawParameterTypes();
    return parameters.size() >= 2;
  }

  private static JavaClass topLevel(JavaClass type) {
    JavaClass top = type;
    while (top.getEnclosingClass().isPresent()) {
      top = top.getEnclosingClass().get();
    }
    return top;
  }

  /**
   * Whether {@code method} checks its caller: its own authorization annotations decide, else those
   * of its class, as Spring Security reads them; a caller parameter counts in either case.
   */
  private static boolean hasCheck(JavaMethod method) {
    Method reflected = method.reflect();
    for (AnnotatedElement element : List.of(reflected, reflected.getDeclaringClass())) {
      List<AnnotationAttributes> found = new ArrayList<>();
      for (String annotation : AUTHORIZATION_ANNOTATIONS) {
        AnnotationAttributes attributes =
            AnnotatedElementUtils.getMergedAnnotationAttributes(element, annotation);
        if (attributes != null) {
          found.add(attributes);
        }
      }
      if (!found.isEmpty()) {
        if (found.stream().noneMatch(attributes -> letsEveryoneIn(attributes.get("value")))) {
          return true;
        }
        break;
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

  private static boolean letsEveryoneIn(Object value) {
    String text =
        value instanceof String[] values ? String.join(" ", values) : String.valueOf(value);
    return LETS_EVERYONE_IN.matcher(text).find();
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
