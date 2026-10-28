plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.jackra1n.dooplex"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.jackra1n.dooplex"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        val crashReportUrl = providers.environmentVariable("DOOPLEX_CRASH_URL").getOrElse("")
        buildConfigField("String", "CRASH_REPORT_URL", "\"$crashReportUrl\"")
        val analyticsUrl = providers.environmentVariable("DOOPLEX_ANALYTICS_URL").getOrElse("")
        buildConfigField("String", "ANALYTICS_URL", "\"$analyticsUrl\"")
        val demoUrl = providers.environmentVariable("DOOPLEX_DEMO_URL").getOrElse("")
        buildConfigField("String", "DEMO_URL", "\"$demoUrl\"")

    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
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
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.tv.material)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}