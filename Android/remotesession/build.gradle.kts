plugins {
    id("com.android.library")
}

android {
    namespace = "dev.remotesession"
    compileSdk = 36
    defaultConfig {
        minSdk = 29
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Compose stays optional: apps that use it already have it, View-only apps don't get it.
    compileOnly(platform("androidx.compose:compose-bom:2026.05.01"))
    compileOnly("androidx.compose.ui:ui")
    // Used when present, for Back at the root and row numbers in the audit log.
    compileOnly("androidx.activity:activity:1.13.0")
    compileOnly("androidx.recyclerview:recyclerview:1.4.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
