plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(libs.versions.jdk.get().toInt()))
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    compileOnly(libs.android.lint.api)
    testImplementation(libs.android.lint.api)
    testImplementation(libs.android.lint.tests)
    testImplementation(libs.junit4)
}
