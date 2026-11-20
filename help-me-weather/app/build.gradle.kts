import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.helpmeweather"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.helpmeweather"
        minSdk = 31
        targetSdk = 37
        versionCode = 20
        versionName = "1.0.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    }

    // Release key lives in keystore/ (git-ignored). Without it, release falls back to the debug key.
    val keystoreProps = Properties().apply {
        rootProject.file("keystore/keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    signingConfigs {
        if (keystoreProps.isNotEmpty()) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
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
            // Never silently ship a debug-signed "release". Local test builds without the key:
            // ./gradlew assembleRelease -PallowDebugSigning=true
            signingConfig = signingConfigs.findByName("release")
                ?: if (providers.gradleProperty("allowDebugSigning").orNull == "true") signingConfigs.getByName("debug")
                else throw GradleException("Release signing key missing (keystore/keystore.properties). Pass -PallowDebugSigning=true for a local test build.")
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
    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.play.location)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.uiautomator)
    androidTestImplementation(libs.work.testing)
}

// Every release build lands in <repo>/releases as help-me-weather-<version>.apk (git-ignored;
// publish it via GitHub Releases).
val appVersion = android.defaultConfig.versionName
val copyReleaseApk = tasks.register<Copy>("copyReleaseApk") {
    from(layout.buildDirectory.dir("outputs/apk/release")) { include("*.apk") }
    into(rootProject.layout.projectDirectory.dir("releases"))
    rename(".*\\.apk", "help-me-weather-$appVersion.apk")
}
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(copyReleaseApk) }
