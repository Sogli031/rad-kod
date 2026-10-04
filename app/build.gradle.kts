plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
    alias(libs.plugins.roborazzi)
    alias(libs.plugins.kover)
}

// Zajednički potpis: Desktop\programi\keystore
val sharedSigning = rootProject.file("../keystore/signing.gradle")
sharedSigning.takeIf { it.exists() }?.let { apply(from = it) }

// Release se ne potpisuje tiho debug ključem: bez zajedničkog ključa build pada.
// CI (nema ključa) prolazi sa -PallowDebugSigning=true.
val hasSharedKey = rootProject.file("../keystore/keystore.properties").exists()
val allowDebugSigning = providers.gradleProperty("allowDebugSigning").orNull == "true"
gradle.taskGraph.whenReady {
    // assemble*Release (APK) i bundle*Release (AAB): oba bi bez ključa dobila debug potpis.
    val buildsRelease = allTasks.any {
        it.project == project && it.name.contains("Release") && (it.name.startsWith("assemble") || it.name.startsWith("bundle"))
    }
    if (buildsRelease && !hasSharedKey && !allowDebugSigning) {
        throw GradleException(
            "Nema ../keystore/keystore.properties: release bi bio potpisan debug ključem. " +
                "Dodaj ključ ili pokreni sa -PallowDebugSigning=true."
        )
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(layout.projectDirectory.file("stability_config.conf"))
    // Izveštaj o stabilnosti: ./gradlew :app:compileReleaseKotlin --rerun -PcomposeReports
    if (providers.gradleProperty("composeReports").isPresent) {
        reportsDestination = layout.buildDirectory.dir("compose_reports")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

android {
    namespace = "com.rad.shifttracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rad.shifttracker"
        minSdk = 26
        targetSdk = 36
        versionCode = 47
        versionName = "5.6"

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
            // BouncyCastle (dolazi uz pdfbox) nosi ~8 MB tabela post-kvantne kriptografije; listić ih ne koristi.
            excludes += "org/bouncycastle/pqc/**"
        }
    }

    // pdfbox: CMap tabele za kineske/japanske/korejske fontove (~3 MB). Listići su TrueType Arial;
    // Identity-H/V (Type0 fontovi) ostaju. Prva stavka je podrazumevana lista aapt-a.
    androidResources {
        ignoreAssetsPattern = listOf(
            "!.svn", "!.git", "!.ds_store", "!*.scc", ".*", "<dir>_*", "!CVS", "!thumbs.db", "!picasa.ini", "!*~",
            "<file>Uni*", "<file>Adobe-*", "<file>GB*", "<file>KSC*", "<file>B5*", "<file>ETen*", "<file>HKscs*",
            "<file>CNS-*", "<file>90*", "<file>83*", "<file>78*", "<file>Add-*", "<file>Ext-*", "<file>EUC-*"
        ).joinToString(":")
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
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
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
    // Samo u debug build-u: curenje memorije (LeakCanary), vidi i StrictMode u RadApplication.
    debugImplementation(libs.leakcanary)
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

// Pokrivenost testovima: obračun plate i podaci ne smeju da padnu ispod praga (koverVerifyDebug, deo `check`).
kover {
    reports {
        filters {
            includes {
                classes(
                    "com.rad.shifttracker.PayCalculator*",
                    "com.rad.shifttracker.ShiftRepository*",
                    "com.rad.shifttracker.data.repository.RepositoryPayQueries*",
                    "com.rad.shifttracker.data.repository.RepositoryPayBaseResolver*",
                    "com.rad.shifttracker.data.storage.ShiftRepositoryJsonCodec*",
                    "com.rad.shifttracker.data.storage.CloudArchiveMerger*",
                    "com.rad.shifttracker.PayslipParserKt*"
                )
            }
        }
        verify {
            rule("Obracun i podaci") {
                minBound(90)
            }
        }
    }
}
