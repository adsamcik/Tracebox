plugins {
    id("tracebox.android.library")
}

android {
    namespace = "dev.tracebox.export.ui"
}

dependencies {
    implementation(project(":android:tracebox-export"))
    testImplementation(kotlin("test-junit"))
    testImplementation(project(":android:tracebox-api"))
    testImplementation(project(":android:tracebox-storage"))
}
