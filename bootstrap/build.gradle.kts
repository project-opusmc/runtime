plugins {
    `java-library`
}

dependencies {
    implementation(project(":common"))
    compileOnly("net.minecraft:launchwrapper:1.12") {
        isTransitive = false
    }

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

repositories {
    maven(url = "https://libraries.minecraft.net/")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "dev.rbw.bootstrap.BootstrapMain"
    }
}
