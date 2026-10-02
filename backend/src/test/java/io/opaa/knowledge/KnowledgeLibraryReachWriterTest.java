package io.opaa.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;

/**
 * The share cap and the external-access release of a {@link KnowledgeLibrary} are written only by
 * the one service per method that writes the matching history and audit entries beside it. The
 * methods are public because their writers live in {@code io.opaa.library}, so this check - not the
 * compiler - holds the boundary. Checked on the compiled main classes, because a method of the same
 * name on another type ({@code KnowledgeLibraryService#updateShareCap}) is no call of the entity.
 */
class KnowledgeLibraryReachWriterTest {

  private static final String ENTITY = "io/opaa/knowledge/KnowledgeLibrary";

  /** Guarded entity method -> the only class allowed to call it, in internal class-name form. */
  private static final Map<String, String> ALLOWED_WRITERS =
      Map.of(
          "updateShareCap", "io/opaa/library/KnowledgeLibraryService",
          "updateExternalAccess", "io/opaa/library/LibraryExternalAccessService",
          "expireExternalAccess", "io/opaa/library/LibraryExternalAccessExpiryService",
          "markExternalAccessReminderSent", "io/opaa/library/LibraryExternalAccessReminderService");

  @Test
  void onlyTheDesignatedServiceChangesEachReachFieldOfALibrary() {
    List<Call> calls = callsOfGuardedMethods();

    assertThat(calls.stream().map(Call::method).distinct())
        .as("every guarded method must actually be found, or the check below proves nothing")
        .containsExactlyInAnyOrderElementsOf(ALLOWED_WRITERS.keySet());
    assertThat(calls)
        .as(
            "only the designated service may change a library's share cap or external-access"
                + " release - it writes the history and audit entries that belong to the change")
        .allMatch(call -> ALLOWED_WRITERS.get(call.method()).equals(call.callerTopLevel()));
  }

  /** A call site of a guarded method; {@code caller} in internal class-name form. */
  private record Call(String caller, String method) {

    /**
     * Lambdas and method references stay in the declaring class, nested and anonymous classes
     * compile into Outer$Inner.
     */
    String callerTopLevel() {
      int nested = caller.indexOf('$');
      return nested < 0 ? caller : caller.substring(0, nested);
    }
  }

  private static List<Call> callsOfGuardedMethods() {
    List<Call> calls = new ArrayList<>();
    try (Stream<Path> files = Files.walk(mainClassesRoot())) {
      files
          .filter(path -> path.toString().endsWith(".class"))
          .forEach(path -> collect(path, calls));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return calls;
  }

  private static void collect(Path classFile, List<Call> calls) {
    try (InputStream in = Files.newInputStream(classFile)) {
      ClassReader reader = new ClassReader(in);
      String caller = reader.getClassName();
      reader.accept(
          new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(
                int access, String name, String descriptor, String signature, String[] ex) {
              return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitMethodInsn(
                    int opcode, String owner, String method, String desc, boolean itf) {
                  if (owner.equals(ENTITY) && ALLOWED_WRITERS.containsKey(method)) {
                    calls.add(new Call(caller, method));
                  }
                }

                /** A method reference names its target only as a bootstrap-argument handle. */
                @Override
                public void visitInvokeDynamicInsn(
                    String name, String desc, Handle bootstrap, Object... bootstrapArgs) {
                  for (Object arg : bootstrapArgs) {
                    if (arg instanceof Handle target
                        && target.getOwner().equals(ENTITY)
                        && ALLOWED_WRITERS.containsKey(target.getName())) {
                      calls.add(new Call(caller, target.getName()));
                    }
                  }
                }
              };
            }
          },
          ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path mainClassesRoot() {
    try {
      return Path.of(
          KnowledgeLibrary.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
