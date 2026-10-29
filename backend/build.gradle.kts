import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    alias(libs.plugins.spring.boot)
}

group = "app.radiance"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation(platform(libs.spring.modulith.bom))
    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation(platform(libs.spring.modulith.bom))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.modulith:spring-modulith-api")
    implementation("org.springframework:spring-jdbc")
    implementation("com.zaxxer:HikariCP")
    implementation("org.xerial:sqlite-jdbc")
    implementation("tools.jackson.dataformat:jackson-dataformat-yaml")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation(libs.jqwik)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial"))
}

// sqlite-jdbc loads its native library through JNI.
val nativeAccess = "--enable-native-access=ALL-UNNAMED"

tasks.test {
    useJUnitPlatform {
        includeEngines("junit-jupiter", "jqwik")
        excludeTags("live") // never call paid APIs in automated runs (SPEC §0)
    }
    systemProperty("user.timezone", "UTC")
    jvmArgs(nativeAccess)
}

// Genesis (SPEC §6.4): ./gradlew genesis --args='--seed 7' [-Pprofile=offline]. See GenesisCommand.
val genesis by tasks.registering(JavaExec::class) {
    description = "Runs genesis: skeleton, flesh-out and import of data/world-seed.json."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "app.radiance.RadianceApplication"
    workingDir = projectDir // same data/ directory as bootRun
    systemProperty("radiance.mode", "genesis")
    systemProperty("spring.profiles.active", providers.gradleProperty("profile").getOrElse("local"))
    jvmArgs(nativeAccess)
}

// The bake-off (SPEC §5.10): ./gradlew llm-bakeoff --args='--purpose=render-thread --models=haiku,<candidate> --fixtures=50'
tasks.register<JavaExec>("llm-bakeoff") {
    description = "Runs the same fixture packets through each candidate model; reports and a blind file in data/bakeoffs/."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "app.radiance.RadianceApplication"
    workingDir = projectDir
    systemProperty("radiance.mode", "bakeoff")
    systemProperty("spring.profiles.active", providers.gradleProperty("profile").getOrElse("local"))
    jvmArgs(nativeAccess)
}

// The tuning harness (SPEC §10): ./gradlew simulate --args='--days=30 --seed=42 --user=scripted:warm'. Offline.
val simulate by tasks.registering(JavaExec::class) {
    description = "Simulates world-days of nightly scripts with a scripted R and prints the feel report."
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "app.radiance.sim.SimulateMain"
    workingDir = projectDir
    jvmArgs(nativeAccess)
}

// Opt-in contract tests against real providers whose keys are in the environment or .env.
val liveTest by tasks.registering(Test::class) {
    description = "Runs @Tag(\"live\") contract tests against real LLM providers (costs a few cents)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeEngines("junit-jupiter")
        includeTags("live")
    }
    systemProperty("user.timezone", "UTC")
    jvmArgs(nativeAccess)
    outputs.upToDateWhen { false }
}

tasks.bootRun {
    jvmArgs(nativeAccess)
}
