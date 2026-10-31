plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Refresh downloads rates straight from Frankfurter. Backup: the rates.json the job in
// this repo publishes (converter/fetch_rates.py). Override the branch with -PratesBranch=...
val frankfurterUrl = "https://api.frankfurter.dev/v1/latest?base=EUR"
val ratesBranch = providers.gradleProperty("ratesBranch").getOrElse("ccr-288fe63e-l5ylhr")
val releaseKeystore: String? = providers.environmentVariable("RELEASE_KEYSTORE").orNull

val ratesUrls = listOf(
    "https://raw.githubusercontent.com/yansen-chandra/cdn/$ratesBranch/converter/rates.json",
    "https://cdn.jsdelivr.net/gh/yansen-chandra/cdn@$ratesBranch/converter/rates.json",
)

android {
    namespace = "io.github.yansenchandra.converter"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.yansenchandra.converter"
        minSdk = 26
        targetSdk = 35
        // CI passes the run number so every build can update the last one.
        versionCode = providers.gradleProperty("versionCode").map { it.toInt() }.getOrElse(1)
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "FRANKFURTER_URL", "\"$frankfurterUrl\"")
        buildConfigField("String", "RATES_URLS", "\"${ratesUrls.joinToString(" ")}\"")
    }

    signingConfigs {
        // Checked-in debug key so every CI build can update the previous install.
        // Not for Play: release builds need their own key (Play App Signing).
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Upload key for Play, from the environment (CI: GitHub secrets). See .github/workflows/android-apk.yml.
        releaseKeystore?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            // Without an upload key the release build is signed with the debug key: installable for
            // testing the shrunk build, but not accepted by Play.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

/** Bundles the shared master list (single source of truth in converter/) as an asset. */
abstract class CopyConverterAssets : DefaultTask() {
    @get:InputFile
    abstract val masterList: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile.apply { mkdirs() }
        masterList.get().asFile.copyTo(File(out, "master-list.json"), overwrite = true)
    }
}

val copyConverterAssets = tasks.register<CopyConverterAssets>("copyConverterAssets") {
    masterList.set(rootProject.layout.projectDirectory.file("../converter/master-list.sample.json"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyConverterAssets, CopyConverterAssets::outputDir)
    }
}

dependencies {
    implementation(project(":engine"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.glance.appwidget)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
