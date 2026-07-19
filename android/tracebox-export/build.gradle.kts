plugins {
    id("tracebox.android.library")
}

android {
    namespace = "dev.tracebox.export"
}

dependencies {
    implementation(project(":android:tracebox-api"))
    implementation(project(":android:tracebox-storage"))
    testImplementation(kotlin("test-junit"))
}
