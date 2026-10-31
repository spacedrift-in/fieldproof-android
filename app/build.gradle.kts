plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// --- Release signing -------------------------------------------------------
// The upload keystore is NEVER committed. CI injects credentials via protected
// environment secrets (see .github/workflows/release.yml and fieldproof-starter/docs/release.md);
// locally you can point the same Gradle properties at a throwaway keystore.
// When no keystore is configured the release build is left unsigned so that a
// bare `./gradlew :app:assembleRelease` still works for smoke testing.
fun envOrProperty(env: String, property: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() }
        ?: (project.findProperty(property) as String?)?.takeIf { it.isNotBlank() }

val uploadKeystorePath = envOrProperty(
    "FIELD_PROOF_UPLOAD_KEYSTORE_PATH", "fieldproof.upload.keystorePath",
)
val uploadKeystoreFile = uploadKeystorePath?.let { file(it) }?.takeIf { it.exists() }
val requireSigning = (project.findProperty("fieldproof.requireSigning") as String?)
    ?.toBoolean() ?: false

if (requireSigning && uploadKeystoreFile == null) {
    throw GradleException(
        "Release signing was requested (fieldproof.requireSigning=true) but no " +
            "upload keystore was found. Set FIELD_PROOF_UPLOAD_KEYSTORE_PATH (and " +
            "the password/alias variables) — see fieldproof-starter/docs/release.md.",
    )
}

val appVersionCode = envOrProperty("FIELD_PROOF_VERSION_CODE", "fieldproof.versionCode")
    ?.toIntOrNull() ?: 1
val appVersionName = envOrProperty("FIELD_PROOF_VERSION_NAME", "fieldproof.versionName")
    ?: "1.0"

android {
    namespace = "com.spacedrift.fieldproof"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.spacedrift.fieldproof"
        minSdk = 24
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName

        // Inject the backend base URL at build time. Debug can use localhost over
        // cleartext for local development; release defaults to HTTPS and must be
        // overridden via environment/property to the production endpoint.
        buildConfigField(
            "String",
            "API_BASE_URL",
            envOrProperty("FIELD_PROOF_API_BASE_URL", "fieldproof.apiBaseUrl")?.let { "\"$it\"" }
                ?: "\"http://127.0.0.1:8000/\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (uploadKeystoreFile != null) {
            create("release") {
                storeFile = uploadKeystoreFile
                storePassword = envOrProperty(
                    "FIELD_PROOF_UPLOAD_KEYSTORE_PASSWORD",
                    "fieldproof.upload.keystorePassword",
                )
                keyAlias = envOrProperty(
                    "FIELD_PROOF_UPLOAD_KEY_ALIAS", "fieldproof.upload.keyAlias",
                )
                keyPassword = envOrProperty(
                    "FIELD_PROOF_UPLOAD_KEY_PASSWORD", "fieldproof.upload.keyPassword",
                )
                // Play requires JAR (v1) + APK Signature Scheme v2 for the AAB/APK.
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
            // Null when no upload keystore is configured → unsigned local build.
            signingConfig = signingConfigs.findByName("release")
            isDebuggable = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.work.runtime.ktx)
    implementation("androidx.hilt:hilt-work:1.4.0")
    ksp("androidx.hilt:hilt-compiler:1.4.0")
    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)

}