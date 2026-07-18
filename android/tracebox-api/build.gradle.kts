plugins {
    id("tracebox.android.library")
}

android {
    namespace = "dev.tracebox.api"
}

dependencies {
    testImplementation(kotlin("test-junit"))
}
