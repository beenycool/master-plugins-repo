dependencies {
    compileOnly(project(":plugins:guab-core"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
    testImplementation("io.papermc.paper:paper-api:26.3.build.240-stable")
}

tasks.test {
    useJUnitPlatform()
}
