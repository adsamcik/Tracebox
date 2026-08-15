package dev.tracebox.lint;

import com.android.tools.lint.checks.infrastructure.LintDetectorTest;
import com.android.tools.lint.checks.infrastructure.TestFiles;
import com.android.tools.lint.checks.infrastructure.TestLintTask;

public final class StaticLogTemplateDetectorTest extends LintDetectorTest {
    @Override
    protected com.android.tools.lint.detector.api.Detector getDetector() {
        return new StaticLogTemplateDetector();
    }

    @Override
    protected java.util.List<com.android.tools.lint.detector.api.Issue> getIssues() {
        return java.util.Collections.singletonList(StaticLogTemplateDetector.ISSUE);
    }

    public void testRejectsRuntimeTemplateTextButAcceptsConstants() {
        TestLintTask.lint()
                .files(
                        TestFiles.kotlin("""
                                package dev.tracebox.api
                                class LogTemplate private constructor(val value: String) {
                                    companion object {
                                        @JvmStatic fun of(value: String): LogTemplate = LogTemplate(value)
                                    }
                                }
                                """).indented(),
                        TestFiles.kotlin("""
                                package consumer
                                import dev.tracebox.api.LogTemplate
                                private const val STATIC_TEMPLATE = "Imported {} points"
                                private val literal = LogTemplate.of("Stopped tracking")
                                private val constant = LogTemplate.of(STATIC_TEMPLATE)
                                fun unsafe(runtimeValue: String) = LogTemplate.of(runtimeValue)
                                """).indented())
                .issues(StaticLogTemplateDetector.ISSUE)
                .allowMissingSdk()
                .run()
                .expect("""
                        src/consumer/test.kt:6: Error: Use a string literal or const val for this Tracebox log template [StaticTraceboxLogTemplate]
                        fun unsafe(runtimeValue: String) = LogTemplate.of(runtimeValue)
                                                                          ~~~~~~~~~~~~
                        1 errors, 0 warnings
                        """.stripIndent());
    }
}
