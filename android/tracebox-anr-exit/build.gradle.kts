plugins {
    id("tracebox.android.library")
}

android {
    namespace = "dev.tracebox.anr"
}

dependencies {
    implementation(project(":android:tracebox-core"))
    testImplementation(kotlin("test-junit"))
}
