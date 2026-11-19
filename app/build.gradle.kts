plugins {
    id("com.android.application") version "9.2.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}
android {
    namespace = "studio.lightdata"
    compileSdk = 37
    defaultConfig {
        applicationId = "studio.lightdata"
        providers.gradleProperty("lightdataAbi").orNull?.let { ndk.abiFilters.add(it) }
        minSdk = 28
        targetSdk = 35
        versionCode = 18
        versionName = "0.2.3-beta"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    buildFeatures { compose = true }
    sourceSets.getByName("main").assets.directories.add("../web/luts")
}
dependencies {
    implementation("org.opencv:opencv:4.12.0")
    implementation("androidx.compose.foundation:foundation:1.8.3")
    implementation("androidx.activity:activity-compose:1.10.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
