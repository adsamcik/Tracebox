plugins {
    `java-gradle-plugin`
}

dependencies {
    testImplementation(gradleTestKit())
}

gradlePlugin {
    plugins {
        create("traceboxIdentity") {
            id = "dev.tracebox.identity"
            implementationClass = "dev.tracebox.gradle.TraceboxIdentityPlugin"
        }

        tasks.register<JavaExec>("identityCaptureTest") {
            group = "verification"
            classpath = sourceSets.test.get().runtimeClasspath
            mainClass.set("dev.tracebox.gradle.BuildIdentityTest")
        }
    }
}
