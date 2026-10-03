plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("io.gitlab.arturbosch.detekt")
    id("io.github.takahirom.roborazzi")
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
        targetSdk = 35
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
    val composeBom = platform("androidx.compose:compose-bom:2025.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3.adaptive:adaptive:1.2.0")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Uvoz platnog listića iz PDF-a (samo lokalna ekstrakcija teksta, bez mreže)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // Google Drive Backup
    implementation("com.google.android.gms:play-services-auth:21.0.0")
    implementation("com.google.api-client:google-api-client-android:2.2.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.apis:google-api-services-drive:v3-rev20231128-2.0.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    // Listići iz Gmail-a (samo čitanje)
    implementation("com.google.apis:google-api-services-gmail:v1-rev20240520-2.0.0") {
        exclude(group = "org.apache.httpcomponents")
    }
    implementation("com.google.http-client:google-http-client-gson:1.43.3") {
        exclude(group = "org.apache.httpcomponents")
    }

    // Unit testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("io.mockk:mockk:1.13.13")
    // Compose UI testovi bez uređaja (pristupačnost: semantika, dodirne površine)
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    // Snimci ekrana na računaru (Robolectric native graphics); vidi DesignSnapshotTest
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.46.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.46.1")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.46.1")

    // Android instrumentation testing
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Referentni snimci su u repou: `recordRoborazziDebug` ih piše, `verifyRoborazziDebug` pada na razliku.
roborazzi {
    outputDir.set(file("src/test/snapshots"))
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/detekt.yml"))
}
