plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ua.vidbiy.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "ua.vidbiy.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes.configureEach {
        // Адреса проксі. Для локального воркера — лише debug і лише явно:
        //   .\gradlew.bat installDebug -Pvidbiy.proxyUrl=http://10.0.2.2:8787
        // Звичайна збірка (і будь-який реліз) завжди ходить на робочий проксі.
        val local = (project.findProperty("vidbiy.proxyUrl") as String?)?.takeIf { name == "debug" }
        buildConfigField("String", "PROXY_URL", "\"${local ?: "https://vidbiy-proxy.nazar-dev.workers.dev"}\"")

        // Тривалість одного дзвінка, хв (FR-21a). Для перевірки на телефоні: -Pvidbiy.ringMinutes=1 (лише debug).
        val ringMinutes = (project.findProperty("vidbiy.ringMinutes") as String?)
            ?.takeIf { name == "debug" }?.toLongOrNull()?.takeIf { it > 0 } ?: 10L
        buildConfigField("long", "RING_MINUTES", "${ringMinutes}L")

        // Резервна копія Android: у debug вимкнена, інакше після перевстановлення
        // повертаються старі тестові дані. Перевірити відновлення: -Pvidbiy.backup=true.
        val backupInDebug = project.findProperty("vidbiy.backup") == "true"
        manifestPlaceholders["allowBackup"] = (name != "debug" || backupInDebug).toString()
    }

    // Release-підпис. Ключ і пароль — лише поза репозиторієм, у %USERPROFILE%\.gradle\gradle.properties:
    //   vidbiy.signing.storeFile=D:/Keys/vidbiy-release.p12
    //   vidbiy.signing.password=...
    // Без них release-збірка виходить непідписаною (app-release-unsigned.apk), а тести працюють як звичайно.
    val signingStoreFile = project.findProperty("vidbiy.signing.storeFile") as String?
    val signingPassword = project.findProperty("vidbiy.signing.password") as String?
    val releaseSigning = if (signingStoreFile != null && signingPassword != null) {
        signingConfigs.create("release") {
            storeFile = file(signingStoreFile)
            storeType = "pkcs12"
            // У PKCS12 пароль сховища й ключа один і той самий.
            storePassword = signingPassword
            keyAlias = "vidbiy"
            keyPassword = signingPassword
        }
    } else null

    buildTypes {
        // Debug — окремий застосунок (ua.vidbiy.app.debug, «Відбій (debug)»): ставиться поруч
        // із release, зі своїми даними й дозволами. Kotlin-пакет класів лишається ua.vidbiy.app,
        // тож у adb компонент пишеться повністю: ua.vidbiy.app.debug/ua.vidbiy.app.MainActivity.
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = releaseSigning
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

// Контрактний тест (AlertsContractTest) читає спільний з сервером приклад відповіді:
// без цього Gradle вважав би тести актуальними після зміни прикладу.
tasks.withType<Test>().configureEach {
    inputs.dir(rootProject.file("docs/fixtures")).withPathSensitivity(PathSensitivity.RELATIVE)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}