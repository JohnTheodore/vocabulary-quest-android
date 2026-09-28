import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localSigningPropertiesFile = rootProject.file(".local-signing/debug.properties")
val localSigningProperties = Properties().apply {
    if (localSigningPropertiesFile.isFile) {
        localSigningPropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.evidencebasedvocabulary.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.evidencebasedvocabulary.app"
        minSdk = 24
        targetSdk = 37
        versionCode = 20
        versionName = "0.9.10"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("debug") {
            // Use the same locally retained certificate for future GitHub APKs.
            // Fresh clones without these gitignored files keep normal Android debug signing.
            if (localSigningPropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("debug").apply {
                    storeFile = rootProject.file(".local-signing/debug.keystore")
                    storePassword = localSigningProperties.getProperty("storePassword")
                    keyAlias = localSigningProperties.getProperty("keyAlias")
                    keyPassword = localSigningProperties.getProperty("keyPassword")
                }
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.material)
    implementation(libs.androidx.webkit)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
