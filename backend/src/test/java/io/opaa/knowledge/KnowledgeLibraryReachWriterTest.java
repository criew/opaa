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
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;

/**
 * The share cap and the external-access release of a {@link KnowledgeLibrary} are written only by
 * the library administration in {@code io.opaa.library}, which writes the matching history and
 * audit entries beside them. Checked on the compiled main classes, because a method of the same
 * name on another type ({@code KnowledgeLibraryService#updateShareCap}) is no call of the entity.
 */
class KnowledgeLibraryReachWriterTest {

  private static final String ENTITY = "io/opaa/knowledge/KnowledgeLibrary";

  private static final String ALLOWED_PACKAGE = "io/opaa/library/";

  private static final Set<String> GUARDED_METHODS =
      Set.of(
          "updateShareCap",
          "updateExternalAccess",
          "expireExternalAccess",
          "markExternalAccessReminderSent");

  @Test
  void onlyTheLibraryAdministrationChangesTheReachOfALibrary() {
    List<String> calls = callsOfGuardedMethods();

    assertThat(calls)
        .as("the calls must actually be found, or the check below proves nothing")
        .anyMatch(call -> call.startsWith(ALLOWED_PACKAGE));
    assertThat(calls)
        .as(
            "only io.opaa.library may change a library's share cap or external-access release -"
                + " it writes the history and audit entries that belong to the change")
        .allMatch(call -> call.startsWith(ALLOWED_PACKAGE));
  }

  /** Every call site as {@code caller-class -> method}, in internal class-name form. */
  private static List<String> callsOfGuardedMethods() {
    List<String> calls = new ArrayList<>();
    try (Stream<Path> files = Files.walk(mainClassesRoot())) {
      files
          .filter(path -> path.toString().endsWith(".class"))
          .forEach(path -> collect(path, calls));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return calls;
  }

  private static void collect(Path classFile, List<String> calls) {
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
                  if (owner.equals(ENTITY) && GUARDED_METHODS.contains(method)) {
                    calls.add(caller + " -> " + method);
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
