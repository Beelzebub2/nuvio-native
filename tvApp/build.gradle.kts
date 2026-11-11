import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

val versionProperties = Properties().apply {
    rootProject.file("composeApp/Configuration/DesktopVersion.properties").forEachLine { line ->
        val parts = line.split('=', limit = 2)
        if (parts.size == 2) setProperty(parts[0].trim(), parts[1].trim())
    }
}

android {
    namespace = "com.starlit.tv"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.android.compileSdkMinor.get().toInt()

    defaultConfig {
        applicationId = "com.starlit.tv"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionName = providers.gradleProperty("nuvio.app.versionName").orNull
            ?: versionProperties.getProperty("VERSION_NAME")
            ?: error("VERSION_NAME missing")
        versionCode = (providers.gradleProperty("nuvio.app.versionCode").orNull
            ?: versionProperties.getProperty("VERSION_CODE"))
            ?.toIntOrNull() ?: error("VERSION_CODE missing")
    }

    sourceSets.getByName("main") {
        // Full distribution includes the bundled Android native streaming engine.
        jniLibs.directories.add("../composeApp/src/full/jniLibs")
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += listOf(
                "lib/*/libc++_shared.so", "lib/*/libavcodec.so", "lib/*/libavutil.so",
                "lib/*/libswscale.so", "lib/*/libswresample.so",
            )
        }
    }
    androidResources.noCompress += "cvr"
    buildTypes.getByName("debug") {
        applicationIdSuffix = ".debug"
    }
    buildTypes.getByName("release") {
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "../composeApp/proguard-rules.pro",
        )
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.appcompat)
    implementation(libs.compose.runtime)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    debugImplementation(libs.compose.uiTooling)
}
