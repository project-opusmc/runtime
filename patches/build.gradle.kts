plugins {
    `java-library`
}

dependencies {
    implementation(project(":bootstrap"))
    implementation(project(":core"))
    implementation(project(":mappings"))
    // Forge 1.8.9 ships ASM 5.0.3. Keep the standalone bootstrap on that
    // same API level so patch bytecode behaves identically in both runtimes.
    implementation("org.ow2.asm:asm:5.0.3")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
