plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.chaquo) apply false
    // Resolved but only applied when a fullPython variant is requested.
}

android {
    namespace = "com.alphaai.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.alphaai.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    flavorDimensions += "python"
    productFlavors {
        create("light") {
            dimension = "python"
            isDefault = true
            buildConfigField("boolean", "FULL_PYTHON", "false")
        }
        create("fullPython") {
            dimension = "python"
            buildConfigField("boolean", "FULL_PYTHON", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjsr305=strict")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.all { test ->
            test.useJUnitPlatform()
        }
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("test") {
            // Unit tests run on the JVM against the light flavor's logic.
        }
    }
}

// Keep Python completely out of normal light builds. Applying Chaquopy is deferred until a
// fullPython variant task is requested or -Palphaai.enableFullPython=true is supplied.
val enableFullPython = providers.gradleProperty("alphaai.enableFullPython").orNull == "true" ||
    gradle.startParameter.taskNames.any { it.contains("fullPython", ignoreCase = true) }
if (enableFullPython) {
    pluginManager.apply("com.chaquo.python")
    extensions.configure<com.chaquo.python.ChaquopyExtension>("chaquopy") {
        productFlavors {
            getByName("fullPython") {
                version = "3.11"
                pip {
                    install("numpy")
                    install("pandas")
                    install("matplotlib")
                }
            }
        }
    }
}


ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    // AndroidX core / lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material)

    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // DataStore / Security / WorkManager / Coil
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)
    implementation(libs.work.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.quickjs)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // fullPython-only dependency (Chaquopy). Declared via the chaquo plugin config in README;
    // intentionally not a universal dependency to keep the light APK small.

    // Testing
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.turbine)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp) // for MockWebServer + interceptor inspection
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.room.runtime)

    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.compose.ui.test.manifest)
}
