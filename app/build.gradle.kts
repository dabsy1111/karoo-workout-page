plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
}

android {
    namespace = "com.dan.workoutpage"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.dan.workoutpage"
        minSdk = 23
        targetSdk = 34
        // Bump both on every change: the Karoo only offers an update when versionCode goes up.
        versionCode = 4
        versionName = "3.1"
    }

    // One shared key, so an APK built anywhere (GitHub Actions, or locally by Claude) can update the
    // copy already on the Karoo; Android refuses updates signed with a different key. The key is NOT
    // in this public repo: GitHub Actions restores it from the WORKOUTPAGE_KEYSTORE secret. Without
    // the file, builds fall back to this computer's debug key and can't update a shared-key install.
    val sharedKey = rootProject.file("keystore/workoutpage.jks")
    signingConfigs {
        if (sharedKey.exists()) {
            create("shared") {
                storeFile = sharedKey
                storePassword = "workoutpage"
                keyAlias = "workoutpage"
                keyPassword = "workoutpage"
            }
        }
    }

    buildTypes {
        val signing = signingConfigs.findByName("shared") ?: signingConfigs.getByName("debug")
        debug {
            signingConfig = signing
        }
        release {
            signingConfig = signing
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(project(":karoo-ext"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.timber)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
}

// Robolectric downloads its Android runtime on first test run; an env var can point it at a Maven mirror.
tasks.withType<Test>().configureEach {
    System.getenv("ROBOLECTRIC_REPO")?.let { systemProperty("robolectric.dependency.repo.url", it) }
    System.getenv("SNAPSHOT_DIR")?.let { environment("SNAPSHOT_DIR", it) }
}
