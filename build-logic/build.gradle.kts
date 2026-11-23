plugins {
    `kotlin-dsl`
}

dependencies {
    testImplementation(kotlin("test"))
}

gradlePlugin {
    plugins {
        register("protoEditions") {
            id = "lens.proto-editions"
            implementationClass = "lens.buildlogic.ProtoEditionsPlugin"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
