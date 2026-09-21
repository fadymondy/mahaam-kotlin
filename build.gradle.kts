plugins {
    kotlin("jvm") version "2.1.20"
    `java-library`
    `maven-publish`
}

group = "app.mahaam"
version = "0.1.0"

repositories {
    mavenCentral()
}

// Deliberately no dependencies. An error reporter that drags in a transitive conflict is an error
// reporter that does not get installed, and this needs nothing the JDK does not already have.
dependencies {
    testImplementation(kotlin("test"))
}

kotlin {
    // Android's minimum in practice. Nothing here needs anything newer.
    jvmToolchain(17)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
    }
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifactId = "mahaam-sdk"
            pom {
                name.set("Mahaam SDK")
                description.set(
                    "Mahaam SDK for Kotlin and Android: uncaught throwables and handled failures " +
                        "become issues on your Mahaam project board.",
                )
                url.set("https://github.com/fadymondy/mahaam-kotlin")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
}
