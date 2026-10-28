plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "io.github.nuccd.scanrecorder"
  defaultConfig {
    applicationId = "io.github.nuccd.scanrecorder"
    versionCode = 8
    versionName = "0.4.2"
    buildConfigField("boolean", "TUNING_ENABLED", "false")
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  buildFeatures {
    viewBinding = true
    buildConfig = true
  }
  buildTypes {
    create("tuning") {
      initWith(getByName("debug"))
      applicationIdSuffix = ".tuning"
      versionNameSuffix = "-tuning"
      matchingFallbacks += "debug"
      buildConfigField("boolean", "TUNING_ENABLED", "true")
    }
    getByName("release") {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  flavorDimensions += "mlkit"
  productFlavors {
    create("bundled").dimension = "mlkit"
  }
  testOptions {
    unitTests.isReturnDefaultValues = true
  }
  testBuildType = "tuning"
}

dependencies {
  implementation(project(":quickie"))
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.core)
  implementation(libs.google.materialDesign)
  testImplementation(libs.test.junit)
  testRuntimeOnly(libs.test.junit.platformLauncher)
  androidTestImplementation(libs.androidx.testRunner)
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
}
