import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// Firebase is optional for local development. Keeping the Google Services
// plugin conditional means a checkout without the project-specific
// google-services.json still builds; realtime signaling continues to work.
val googleServicesFile = file("google-services.json")
if (googleServicesFile.exists()) {
    apply(plugin = "com.google.gms.google-services")
} else {
    logger.warn("google-services.json not found in app/; Firebase push is disabled for this build.")
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

// Machine/demo-specific values override the normal Android local.properties.
// Keeping this as an explicit second load prevents a stale LAN address in
// local.properties from silently winning over the public demo endpoint.
val localOverridesFile = rootProject.file("local.properties.local")
if (localOverridesFile.exists()) {
    localProperties.load(FileInputStream(localOverridesFile))
}

android {
    namespace = "com.example.tv_caller_app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.tv_caller_app"
        minSdk = 25
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val supabaseUrl = localProperties.getProperty("supabase.url") ?: ""
        val supabaseKey = localProperties.getProperty("supabase.key") ?: ""
        val backendBaseUrl = localProperties.getProperty("backend.base.url") ?: ""

        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_KEY", "\"$supabaseKey\"")
        buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")
        buildConfigField("boolean", "FIREBASE_CONFIGURED", googleServicesFile.exists().toString())

    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    configurations.all {
        resolutionStrategy {
            force("androidx.appcompat:appcompat:1.1.0")
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.leanback)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.recyclerview)

    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.supabase.postgrest.kt)
    implementation(libs.supabase.gotrue.kt)
    implementation(libs.supabase.realtime.kt)
    implementation(libs.supabase.storage.kt)
    implementation(libs.supabase.functions.kt)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging.ktx)

    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)  
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.security.crypto)

    implementation(libs.webrtc)

    implementation(libs.glide)

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.espresso.accessibility)
    androidTestImplementation(libs.espresso.contrib)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
}
