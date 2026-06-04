plugins {
    id("configure-jvm-library-module")
}

dependencies {
    api(project(":evokammer:api"))
    implementation(project(":evokammer:agents"))
    implementation(project(":evokammer:competition"))
    implementation(project(":evokammer:orchestration"))
    implementation(project(":evokammer:sandbox"))
    implementation(project(":evokammer:substrate"))

    implementation(libs.napier)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.koog)
}
