import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.helpmecalendar"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.helpmecalendar"
        minSdk = 31
        targetSdk = 37
        versionCode = 7
        versionName = "0.1.6"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    // One release key for every widget, in keystore/ at the repo root (git-ignored). Without it,
    // release falls back to the debug key.
    val keystoreProps = Properties().apply {
        rootProject.file("../keystore/keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    // A usable key needs every property and the keystore file; anything less counts as missing.
    val hasReleaseKey = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { !keystoreProps.getProperty(it).isNullOrBlank() } &&
        rootProject.file("../keystore/" + keystoreProps.getProperty("storeFile")).isFile
    signingConfigs {
        if (hasReleaseKey) create("release") {
            storeFile = rootProject.file("../keystore/" + keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Never silently ship a debug-signed "release" (checked when a release is packaged, so
            // debug builds work without the key). Local test builds: -PallowDebugSigning=true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    // Glance renders widgets in WorkManager jobs; pin the current version (the transitive one
    // crashed the minified 1.0.0 build at startup: WorkDatabase could not be created).
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.uiautomator)
}

// Every release build lands in <repo>/releases as help-me-calendar-<version>.apk (git-ignored;
// publish it via GitHub Releases).
val appVersion = android.defaultConfig.versionName
val missingReleaseKey = android.signingConfigs.findByName("release") == null &&
    providers.gradleProperty("allowDebugSigning").orNull != "true"
tasks.matching { it.name == "packageRelease" || it.name == "packageReleaseBundle" }.configureEach {
    val missing = missingReleaseKey // local copy: the configuration cache can't capture the script
    doFirst {
        if (missing) throw GradleException("Release signing key missing (keystore/keystore.properties). Pass -PallowDebugSigning=true for a local test build.")
    }
}
val copyReleaseApk = tasks.register<Copy>("copyReleaseApk") {
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*.apk") }
    into(rootProject.layout.projectDirectory.dir("releases"))
    rename(".*\\.apk", "help-me-calendar-$appVersion.apk")
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(copyReleaseApk) }
