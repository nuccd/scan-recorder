plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "io.github.nuccd.scanrecorder"
  defaultConfig {
    applicationId = "io.github.nuccd.scanrecorder"
    versionCode = 11
    versionName = "0.5.0"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  buildFeatures {
    viewBinding = true
    buildConfig = true
  }
  buildTypes {
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
