plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

val searchApiBaseUrl = providers.gradleProperty("ATLAS_SEARCH_API_BASE_URL").orElse("").get()
val updateManifestUrl = providers.gradleProperty("ATLAS_UPDATE_MANIFEST_URL").orElse("").get()
val atlasVersionCode = providers.gradleProperty("ATLAS_VERSION_CODE").orElse("1").get().toInt()
val atlasVersionName = providers.gradleProperty("ATLAS_VERSION_NAME").orElse("1.0.0").get()
val releaseStoreFilePath = providers.environmentVariable("ATLAS_RELEASE_STORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("ATLAS_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ATLAS_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ATLAS_RELEASE_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(releaseStoreFilePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword).all { !it.isNullOrBlank() }

require(atlasVersionCode > 0) { "ATLAS_VERSION_CODE must be a positive integer." }
require(atlasVersionName.isNotBlank() && atlasVersionName.length <= 40) { "ATLAS_VERSION_NAME must contain 1 to 40 characters." }

android {
    namespace = "com.knowledgeatlas.app"
    compileSdk = 36

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = file(requireNotNull(releaseStoreFilePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    defaultConfig {
        applicationId = "com.knowledgeatlas.app"
        minSdk = 28
        targetSdk = 36
        versionCode = atlasVersionCode
        versionName = atlasVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SEARCH_API_BASE_URL", "\"$searchApiBaseUrl\"")
        buildConfigField("String", "UPDATE_MANIFEST_URL", "\"$updateManifestUrl\"")
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("direct") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "SELF_UPDATE_ENABLED", "false")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.04.01"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    testImplementation("junit:junit:4.13.2")
}
