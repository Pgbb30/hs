/*
 * Copyright (C) 2020-2023 Hedera Hashgraph, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
    id("com.hedera.hashgraph.conventions")
    id("com.hedera.hashgraph.benchmark-conventions")
    `java-test-fixtures`
}

description = "Hedera Application - Implementation"

dependencies {
    javaModuleDependencies {
        annotationProcessor(gav("dagger.compiler"))

        // This is needed to pick up and include the native libraries for the netty epoll transport
        runtimeOnly(gav("io.netty.transport.epoll"))

        testImplementation(project(":app"))
        testImplementation(testFixtures(project(":config")))
        testImplementation(testFixtures(project(":app-service-mono")))
        testImplementation(testFixtures(project(":app-spi")))
        testImplementation(gav("com.google.jimfs"))
        testImplementation(gav("com.swirlds.base"))
        testImplementation(gav("io.github.classgraph"))
        testImplementation(gav("org.assertj.core"))
        testImplementation(gav("org.hamcrest"))
        testImplementation(gav("org.junit.jupiter.api"))
        testImplementation(gav("org.junit.jupiter.params"))
        testImplementation(gav("org.mockito"))
        testImplementation(gav("org.mockito.junit.jupiter"))
        testImplementation(gav("uk.org.webcompere.systemstubs.jupiter"))
        testImplementation(gav("uk.org.webcompere.systemstubs.core"))
        testCompileOnly(gav("com.github.spotbugs.annotations"))

        itestImplementation(project(":app"))
        itestImplementation(project(":app-spi"))
        itestImplementation(project(":config"))
        itestImplementation(project(":hapi"))
        itestImplementation(testFixtures(project(":app-spi")))
        itestImplementation(testFixtures(project(":config")))
        itestImplementation(gav("com.github.spotbugs.annotations"))
        itestImplementation(gav("com.hedera.pbj.runtime"))
        itestImplementation(gav("com.swirlds.common"))
        itestImplementation(gav("com.swirlds.config"))
        itestImplementation(gav("io.grpc"))
        itestImplementation(gav("org.apache.logging.log4j"))
        itestImplementation(gav("org.assertj.core"))
        itestImplementation(gav("org.bouncycastle.provider"))
        itestImplementation(gav("org.junit.jupiter.api"))
        itestImplementation(gav("org.junit.jupiter.params"))
        itestImplementation(gav("io.netty.transport.classes.epoll"))
        itestImplementation(gav("io.netty.transport.epoll"))

        jmhImplementation(project(":app"))
        jmhImplementation(project(":app-service-mono"))
        jmhImplementation(project(":hapi"))
        jmhImplementation(testFixtures(project(":app-spi")))
        jmhImplementation(gav("com.hedera.pbj.runtime"))
        jmhImplementation(gav("com.swirlds.common"))
        jmhImplementation(gav("jmh.core"))
    }
}

tasks.withType<Test> {
    testLogging.exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
}

// Add all the libs dependencies into the jar manifest!
tasks.jar {
    doFirst {
        tasks.jar.configure {
            manifest {
                attributes(
                    "Main-Class" to "com.hedera.node.app.ServicesMain",
                    "Class-Path" to
                        configurations.getByName("runtimeClasspath").joinToString(separator = " ") {
                            "../../data/lib/" + it.name
                        }
                )
            }
        }
    }
}

// Copy dependencies into `data/lib`
val copyLib =
    tasks.register<Copy>("copyLib") {
        from(project.configurations.getByName("runtimeClasspath"))
        into(project(":hedera-node").file("data/lib"))
    }

// Copy built jar into `data/apps` and rename HederaNode.jar
val copyApp =
    tasks.register<Copy>("copyApp") {
        from(tasks.jar)
        into(project(":hedera-node").file("data/apps"))
        rename { "HederaNode.jar" }
        shouldRunAfter(tasks.getByName("copyLib"))
    }

tasks.assemble {
    dependsOn(copyLib)
    dependsOn(copyApp)
}

val generatedSources = file("build/generated/sources/annotationProcessor/java/main")

java.sourceSets["main"].java.srcDir(generatedSources)

// Create the "run" task for running a Hedera consensus node
tasks.register<JavaExec>("run") {
    group = "application"
    dependsOn(tasks.assemble)
    workingDir = project(":hedera-node").projectDir
    jvmArgs = listOf("-cp", "data/lib/*")
    mainClass.set("com.swirlds.platform.Browser")
}

tasks.register<JavaExec>("modrun") {
    group = "application"
    dependsOn(tasks.assemble)
    workingDir = project(":hedera-node").projectDir
    jvmArgs = listOf("-cp", "data/lib/*", "-Dhedera.workflows.enabled=true")
    mainClass.set("com.swirlds.platform.Browser")
}

val cleanRun =
    tasks.register("cleanRun") {
        val prj = project(":hedera-node")
        prj.delete(File(prj.projectDir, "database"))
        prj.delete(File(prj.projectDir, "output"))
        prj.delete(File(prj.projectDir, "settingsUsed.txt"))
        prj.delete(File(prj.projectDir, "swirlds.jar"))
        prj.projectDir
            .list { _, fileName -> fileName.startsWith("MainNetStats") }
            ?.forEach { file -> prj.delete(file) }

        val dataDir = File(prj.projectDir, "data")
        prj.delete(File(dataDir, "accountBalances"))
        prj.delete(File(dataDir, "apps"))
        prj.delete(File(dataDir, "lib"))
        prj.delete(File(dataDir, "recordstreams"))
        prj.delete(File(dataDir, "saved"))
    }

tasks.clean { dependsOn(cleanRun) }

tasks.register("showHapiVersion") { doLast { println(libs.versions.hapi.proto.get()) } }

var updateDockerEnvTask =
    tasks.register<Exec>("updateDockerEnv") {
        description =
            "Creates the .env file in the docker folder that contains environment variables for docker"
        group = "docker"

        workingDir("${rootProject.projectDir}/hedera-node/docker")
        commandLine("./update-env.sh", project.version)
    }

tasks.register<Exec>("createDockerImage") {
    description = "Creates the docker image of the services based on the current version"
    group = "docker"

    dependsOn(updateDockerEnvTask, tasks.assemble)
    workingDir("${rootProject.projectDir}/hedera-node/docker")
    commandLine("./docker-build.sh", project.version, rootProject.projectDir)
}

tasks.register<Exec>("startDockerContainers") {
    description = "Starts docker containers of the services based on the current version"
    group = "docker"

    dependsOn(updateDockerEnvTask)
    workingDir("${rootProject.projectDir}/hedera-node/docker")
    commandLine("docker-compose", "up")
}

tasks.register<Exec>("stopDockerContainers") {
    description = "Stops running docker containers of the services"
    group = "docker"

    dependsOn(updateDockerEnvTask)
    workingDir("${rootProject.projectDir}/hedera-node/docker")
    commandLine("docker-compose", "stop")
}
