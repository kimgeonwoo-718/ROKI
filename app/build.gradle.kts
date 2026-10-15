import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Which model the app uses (see ModelCatalog). Override in gradle.properties or with -Proki.modelId=...
val modelId = providers.gradleProperty("roki.modelId").getOrElse("gemma-4-e2b")
// Optional download URL override. Empty = use the pinned Hugging Face URL from ModelCatalog.
val modelDownloadUrl = providers.gradleProperty("roki.modelDownloadUrl").getOrElse("")

android {
    namespace = "io.github.kimgeonwoo718.roki"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.kimgeonwoo718.roki"
        minSdk = 31
        targetSdk = 36
        versionCode = 4
        versionName = "0.2.0"

        buildConfigField("String", "MODEL_ID", "\"$modelId\"")
        buildConfigField("String", "MODEL_DOWNLOAD_URL", "\"$modelDownloadUrl\"")

        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    packaging { jniLibs { pickFirsts.add("**/liblitertlm_jni.so") } }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.litertlm)
    implementation(libs.markdown.renderer.m3)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

room {
    schemaDirectory("$projectDir/schemas")
}
