package dev.haris.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * Forgetting to mark outside content is how list_folder and search_files leaked trust (F2, F3).
 * Every tool must be placed on exactly one of these lists, so a new tool cannot skip the decision.
 */
class ToolTrustDeclarationTest {

    /** Output can carry text someone other than the user chose. These override readsOutsideContent(). */
    private static final Set<String> OUTSIDE_CONTENT = Set.of(
            "dev.haris.tools.impl.ReadFileTool",
            "dev.haris.tools.impl.ClipboardReadTool",
            "dev.haris.tools.impl.ListFolderTool",
            "dev.haris.tools.impl.SearchFilesTool",
            "dev.haris.tools.impl.ListAppsTool",
            "dev.haris.tools.impl.WeatherTool",
            "dev.haris.web.WebSearchTool",
            "dev.haris.web.FetchPageTool",
            "dev.haris.vision.LookAtScreenTool",
            "dev.haris.vision.LookThroughCameraTool");

    /** Output is Haris's own wording or content the user approved. These keep the default. */
    private static final Set<String> TRUSTED_OUTPUT = Set.of(
            "dev.haris.tools.impl.ClipboardWriteTool",
            "dev.haris.tools.impl.ComposeMessageTool",
            "dev.haris.tools.impl.CreateFolderTool",
            "dev.haris.tools.impl.DeleteFileTool",
            "dev.haris.tools.impl.MediaControlTool",
            "dev.haris.tools.impl.MoveFileTool",
            "dev.haris.tools.impl.OpenAppTool",
            "dev.haris.tools.impl.OpenUrlTool",
            "dev.haris.tools.impl.PowerTool",
            "dev.haris.tools.impl.RemindersTool",
            "dev.haris.tools.impl.SystemStatusTool",
            "dev.haris.tools.impl.WriteFileTool",
            "dev.haris.memory.RememberTool",
            "dev.haris.memory.RecallTool",
            "dev.haris.memory.ForgetTool");

    @Test
    void everyToolIsOnExactlyOneList() {
        Set<String> found = toolClasses();
        Set<String> listed = new HashSet<>(OUTSIDE_CONTENT);
        listed.addAll(TRUSTED_OUTPUT);

        Set<String> undecided = new TreeSet<>(found);
        undecided.removeAll(listed);
        assertThat(undecided)
                .as("Decide whether these tools read outside content and add them to one of the lists")
                .isEmpty();
        assertThat(OUTSIDE_CONTENT).as("a tool cannot be on both lists").doesNotContainAnyElementsOf(TRUSTED_OUTPUT);
        assertThat(found).as("listed classes that no longer exist").containsAll(listed);
    }

    @Test
    void declarationsMatchTheLists() throws ClassNotFoundException {
        for (String name : OUTSIDE_CONTENT) {
            assertThat(declaresReadsOutsideContent(name)).as(name + " must override readsOutsideContent()").isTrue();
        }
        for (String name : TRUSTED_OUTPUT) {
            assertThat(declaresReadsOutsideContent(name)).as(name + " must keep the default").isFalse();
        }
    }

    private static boolean declaresReadsOutsideContent(String className) throws ClassNotFoundException {
        // initialize = false: only the declaration is inspected, no tool code runs
        Class<?> type = Class.forName(className, false, ToolTrustDeclarationTest.class.getClassLoader());
        return Arrays.stream(type.getDeclaredMethods())
                .anyMatch(m -> m.getName().equals("readsOutsideContent") && m.getParameterCount() == 0);
    }

    /** Concrete Tool classes under dev.haris, without the fakes nested in test classes. */
    private static Set<String> toolClasses() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isConcrete() && definition.getMetadata().isIndependent();
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(Tool.class));
        Set<String> out = new TreeSet<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("dev.haris")) {
            String name = definition.getBeanClassName();
            String outer = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
            if (!outer.endsWith("Test")) {
                out.add(name);
            }
        }
        return out;
    }
}
