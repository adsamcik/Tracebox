package dev.tracebox.lint;

import com.android.tools.lint.detector.api.Category;
import com.android.tools.lint.detector.api.ConstantEvaluator;
import com.android.tools.lint.detector.api.Detector;
import com.android.tools.lint.detector.api.Implementation;
import com.android.tools.lint.detector.api.Issue;
import com.android.tools.lint.detector.api.JavaContext;
import com.android.tools.lint.detector.api.Scope;
import com.android.tools.lint.detector.api.Severity;
import com.intellij.psi.PsiMethod;

import java.util.Collections;
import java.util.List;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.uast.UCallExpression;
import org.jetbrains.uast.UExpression;

/** Requires Tracebox log templates to be compile-time constant developer-authored text. */
public final class StaticLogTemplateDetector extends Detector implements Detector.UastScanner {
    private static final String LOG_TEMPLATE_CLASS = "dev.tracebox.api.LogTemplate";

    public static final Issue ISSUE = Issue.create(
            "StaticTraceboxLogTemplate",
            "Tracebox log templates must be static",
            "Only compile-time constant text may be passed to `LogTemplate.of`. Put runtime "
                    + "values in privacy-classified `LogArgument` parameters so Tracebox can "
                    + "transform them before Logcat or durable storage.",
            Category.SECURITY,
            9,
            Severity.ERROR,
            new Implementation(StaticLogTemplateDetector.class, Scope.JAVA_FILE_SCOPE));

    @Override
    public List<String> getApplicableMethodNames() {
        return Collections.singletonList("of");
    }

    @Override
    public void visitMethodCall(
            @NotNull JavaContext context,
            @NotNull UCallExpression node,
            @NotNull PsiMethod method) {
        if (method.getContainingClass() == null) {
            return;
        }
        String owner = method.getContainingClass().getQualifiedName();
        if (owner == null || (!owner.equals(LOG_TEMPLATE_CLASS)
                && !owner.equals(LOG_TEMPLATE_CLASS + ".Companion"))) {
            return;
        }
        List<UExpression> arguments = node.getValueArguments();
        if (arguments.size() != 1) {
            return;
        }
        UExpression value = arguments.get(0);
        if (!(ConstantEvaluator.evaluate(context, value) instanceof String)) {
            context.report(
                    ISSUE,
                    value,
                    context.getLocation(value),
                    "Use a string literal or `const val` for this Tracebox log template");
        }
    }
}
