plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
    alias(libs.plugins.roborazzi)
}

// Zajednički potpis: Desktop\programi\keystore
val sharedSigning = rootProject.file("../keystore/signing.gradle")
sharedSigning.takeIf { it.exists() }?.let { apply(from = it) }

// Release se ne potpisuje tiho debug ključem: bez zajedničkog ključa build pada.
// CI (nema ključa) prolazi sa -PallowDebugSigning=true.
val hasSharedKey = rootProject.file("../keystore/keystore.properties").exists()
val allowDebugSigning = providers.gradleProperty("allowDebugSigning").orNull == "true"
gradle.taskGraph.whenReady {
    val buildsRelease = allTasks.any { it.project == project && it.name.contains("Release") && it.name.startsWith("assemble") }
    if (buildsRelease && !hasSharedKey && !allowDebugSigning) {
        throw GradleException(
            "Nema ../keystore/keystore.properties: release bi bio potpisan debug ključem. " +
                "Dodaj ključ ili pokreni sa -PallowDebugSigning=true."
        )
    }
}

android {
    namespace = "com.rad.shifttracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rad.shifttracker"
        minSdk = 26
        targetSdk = 36
        versionCode = 46
        versionName = "5.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/license.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
            excludes += "/META-INF/notice.txt"
            excludes += "/META-INF/INDEX.LIST"
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
        // NullSafeMutableLiveData: lint-provera iz lifecycle biblioteke puca (IncompatibleClassChangeError) na AGP 8.7; LiveData se ne koristi.
        disable += setOf("ObsoleteLintCustomCheck", "NullSafeMutableLiveData")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric + Compose UI testovi na računaru traže resurse (tema, manifest) u unit testovima.
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.text)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.google.material)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)

    // Uvoz platnog listića iz PDF-a (samo lokalna ekstrakcija teksta, bez mreže)
    implementation(libs.pdfbox.android)

    // Google Drive Backup
    implementation(libs.play.services.auth)
    implementation(libs.google.api.client.android) {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation(libs.google.api.drive) {
        exclude(group = "org.apache.httpcomponents")
    }
    // Listići iz Gmail-a (samo čitanje)
    implementation(libs.google.api.gmail) {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation(libs.google.http.client.gson) {
        exclude(group = "org.apache.httpcomponents")
    }

    // Unit testing
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    // Compose UI testovi bez uređaja (pristupačnost: semantika, dodirne površine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Snimci ekrana na računaru (Robolectric native graphics); vidi DesignSnapshotTest
    testImplementation(libs.roborazzi.core)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)

    // Android instrumentation testing
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

// Referentni snimci su u repou: `recordRoborazziDebug` ih piše, `verifyRoborazziDebug` pada na razliku.
roborazzi {
    outputDir.set(file("src/test/snapshots"))
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/detekt.yml"))
}
