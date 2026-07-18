package dev.tracebox.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;

/** Minimal Gradle boundary for future AGP Variant API identity tasks. */
public final class TraceboxIdentityPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getExtensions().create("traceboxIdentity", TraceboxIdentityExtension.class);
    }
}
