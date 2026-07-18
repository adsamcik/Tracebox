plugins {
    id("tracebox.android.library")
}

android {
    namespace = "dev.tracebox.directboot"
}

dependencies {
    implementation(project(":android:tracebox-core"))
    testImplementation(kotlin("test-junit"))
}
