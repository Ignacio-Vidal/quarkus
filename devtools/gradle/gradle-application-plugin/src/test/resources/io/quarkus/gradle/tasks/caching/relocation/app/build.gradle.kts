plugins {
    java
    id("io.quarkus")
}

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation(enforcedPlatform("io.quarkus:quarkus-bom:${project.property("version")}"))
    implementation("io.quarkus:quarkus-arc")
    // a sibling module, whose jar sits outside this project's directory
    implementation(project(":common"))
    // ADDITIONAL_DEPENDENCIES

    testImplementation("io.quarkus:quarkus-junit")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
