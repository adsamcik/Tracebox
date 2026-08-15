plugins {
    `java-gradle-plugin`
}

dependencies {
    implementation(libs.android.gradle.plugin)
    testImplementation(gradleTestKit())
    testImplementation(libs.junit4)
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
