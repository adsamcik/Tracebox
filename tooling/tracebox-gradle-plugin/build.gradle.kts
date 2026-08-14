plugins {
    `java-gradle-plugin`
}

dependencies {
    implementation("com.android.tools.build:gradle:9.2.0")
    testImplementation(gradleTestKit())
    testImplementation("junit:junit:4.13.2")
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
