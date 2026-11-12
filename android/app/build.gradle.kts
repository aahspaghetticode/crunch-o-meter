import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    // No org.jetbrains.kotlin.android: AGP 9 has Kotlin built in.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Release signing comes from android/keystore.properties (git-ignored) or, in CI,
 * from environment variables. Without either, release builds fall back to the
 * debug key so `assembleRelease` still produces something installable.
 */
val signing: Map<String, String?> = run {
    val file = rootProject.file("keystore.properties")
    val props = Properties().apply { if (file.isFile) file.inputStream().use(::load) }
    fun value(key: String, env: String) = props.getProperty(key) ?: System.getenv(env)
    mapOf(
        "storeFile" to value("storeFile", "CRUNCH_KEYSTORE_FILE"),
        "storePassword" to value("storePassword", "CRUNCH_KEYSTORE_PASSWORD"),
        "keyAlias" to value("keyAlias", "CRUNCH_KEY_ALIAS"),
        "keyPassword" to value("keyPassword", "CRUNCH_KEY_PASSWORD"),
    )
}
val hasReleaseKey = signing.values.all { !it.isNullOrBlank() }

android {
    namespace = "com.henw.crunchometer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.henw.crunchometer"
        minSdk = 26 // dynamic (Material You) colour on Android 12+, a fixed palette below that
        targetSdk = 36
        // The release workflow passes these from the git tag; local builds use the defaults.
        versionCode = (findProperty("crunch.versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (findProperty("crunch.versionName") as String?) ?: "1.0.0"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = rootProject.file(signing.getValue("storeFile")!!)
                storePassword = signing["storePassword"]
                keyAlias = signing["keyAlias"]
                keyPassword = signing["keyPassword"]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("No release key (keystore.properties or CRUNCH_* env vars): signing release with the debug key")
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
