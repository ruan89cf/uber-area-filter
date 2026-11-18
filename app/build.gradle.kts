import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ruan.uberareafilter"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ruan.uberareafilter"
        minSdk = 30
        targetSdk = 34
        versionCode = 21
        versionName = "2.7"
    }

    signingConfigs {
        create("release") {
            // Senhas fora do codigo: ficam em keystore.properties (na raiz do projeto, ignorado pelo git)
            val ksProps = Properties()
            val ksPropsFile = rootProject.file("keystore.properties")
            if (ksPropsFile.exists()) {
                ksPropsFile.inputStream().use { stream -> ksProps.load(stream) }
            }
            val ksFile = file(ksProps.getProperty("storeFile", "release-key.jks"))
            val storePass = ksProps.getProperty("storePassword") ?: System.getenv("FILTRO_STORE_PASSWORD")
            if (ksFile.exists() && storePass != null) {
                storeFile = ksFile
                storePassword = storePass
                keyAlias = ksProps.getProperty("keyAlias") ?: System.getenv("FILTRO_KEY_ALIAS")
                keyPassword = ksProps.getProperty("keyPassword") ?: System.getenv("FILTRO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    }

    buildFeatures {
        viewBinding = true
        aidl = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    testImplementation("junit:junit:4.13.2")
}

// TEMPORARIO: copia os fontes Kotlin para uma pasta rasa na raiz do projeto, apenas para leitura
// durante a revisao de codigo. Nao altera nada em src/. Pode ser removido junto com a pasta
// _fontes_espelho quando a revisao terminar.
val espelharFontes = tasks.register<Copy>("espelharFontes") {
    from("src/main/java/com/ruan/uberareafilter")
    into(rootProject.layout.projectDirectory.dir("_fontes_espelho"))
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(espelharFontes) }
