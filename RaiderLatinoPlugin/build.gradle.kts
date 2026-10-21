apply(plugin = "com.android.application")
apply(plugin = "kotlin-android")
apply(plugin = "cloudstream")

cloudstream {
    setLanguage("es")
    setRequiresResources(false)
}

android {
    namespace = "com.raiderlatino.plugin"
    compileSdk = 33

    defaultConfig {
        applicationId = "com.raiderlatino.plugin"
        minSdk = 21
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.github.recloudstream:cloudstream:master-SNAPSHOT")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.10")
    implementation("com.github.Blatzar:NiceHttp:0.4.11")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
}
