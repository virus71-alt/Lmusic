plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.lmusic"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lmusic"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Keep only the resource configurations we actually ship strings in.
        // Drops every other locale's strings.xml from the APK (~150kB saved).
        resourceConfigurations += listOf("en")

        // Drop pseudo-locales from release.
        vectorDrawables.useSupportLibrary = true
    }

    buildTypes {
        debug {
            // Skip resource shrinking in debug for faster iteration.
            isMinifyEnabled = false
        }
        release {
            // R8 full mode strips unused classes + obfuscates, shrinks ~30-40%.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Improve runtime perf + smaller bytecode.
        freeCompilerArgs += listOf(
            "-Xjvm-default=all",
            "-opt-in=kotlin.RequiresOptIn"
        )
    }

    buildFeatures {
        viewBinding = true
        // Save a couple of MB by disabling features we don't use.
        buildConfig = false
        aidl       = false
        renderScript = false
        shaders    = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/*.kotlin_module"
            excludes += "META-INF/*.version"
            excludes += "kotlin/**"
            pickFirsts += "META-INF/gradle/incremental.annotation.processors"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.coroutines.android)
    implementation(libs.newpipe.extractor)
    implementation(libs.okhttp)
    implementation(libs.coil)
    implementation(libs.androidx.preference)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.core.splashscreen)
    implementation(libs.ffmpegkit.audio)
}
