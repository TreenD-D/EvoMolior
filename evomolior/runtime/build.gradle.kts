plugins {
    id("configure-jvm-library-module")
}

dependencies {
    api(project(":engine:api"))
    implementation(project(":engine:agents"))
    implementation(project(":engine:competition"))
    implementation(project(":engine:orchestration"))
    implementation(project(":engine:sandbox"))
    implementation(project(":engine:substrate"))

    implementation(libs.napier)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koog)

    testImplementation(kotlin("test"))
}
