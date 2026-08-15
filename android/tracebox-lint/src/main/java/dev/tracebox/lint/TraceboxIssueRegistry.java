package dev.tracebox.lint;

import com.android.tools.lint.client.api.IssueRegistry;
import com.android.tools.lint.client.api.Vendor;
import com.android.tools.lint.detector.api.Issue;

import java.util.Collections;
import java.util.List;

import org.jetbrains.annotations.NotNull;

/** Publishes Tracebox's privacy contract checks to consuming Android builds. */
public final class TraceboxIssueRegistry extends IssueRegistry {
    @NotNull
    @Override
    public Vendor getVendor() {
        return new Vendor(
                "Tracebox",
                "dev.tracebox",
                "https://github.com/adsamcik/Tracebox/issues",
                "https://github.com/adsamcik/Tracebox");
    }

    @Override
    public int getApi() {
        return com.android.tools.lint.detector.api.ApiKt.CURRENT_API;
    }

    @NotNull
    @Override
    public List<Issue> getIssues() {
        return Collections.singletonList(StaticLogTemplateDetector.ISSUE);
    }
}
