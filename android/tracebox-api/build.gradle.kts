plugins {
    id("tracebox.android.library")
}

android {
    namespace = "dev.tracebox.api"
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test-junit"))
}
