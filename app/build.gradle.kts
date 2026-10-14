plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.fengine.wallpaper"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.fengine.wallpaper"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Property seeds are printed on failure so a counterexample can be replayed.
    testLogging { events("failed"); showStandardStreams = false }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Material 3 1.4+ no longer pulls the icon set in; Close is the only icon the UI uses.
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    // On-device only: ML Kit base image labeling (bundled model) and the Gemini Nano Prompt API.
    implementation(libs.mlkit.image.labeling)
    implementation(libs.mlkit.genai.prompt)
    testImplementation(libs.junit)
    // Invariant / fuzz suite: jqwik properties on the JUnit platform; JUnit 4 tests keep running via vintage.
    testImplementation(libs.jqwik)
    testImplementation(libs.junit.vintage.engine)
    testRuntimeOnly(libs.junit.platform.launcher)
    // Real org.json for JVM tests; android.jar ships stubs that throw.
    testImplementation("org.json:json:20260814")
}
