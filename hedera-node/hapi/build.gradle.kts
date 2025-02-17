/*
 * Copyright (C) 2022-2023 Hedera Hashgraph, LLC
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
    id("com.hedera.hashgraph.hapi")
    @Suppress("DSL_SCOPE_VIOLATION") alias(libs.plugins.pbj)
    `java-test-fixtures`
}

description = "Hedera API"

dependencies {
    javaModuleDependencies {
        testImplementation(project(":hapi"))
        // we depend on the protoc compiled hapi during test as we test our pbj generated code
        // against it to make sure it is compatible
        testImplementation(gav("com.google.protobuf.util"))
        testImplementation(gav("org.junit.jupiter.api"))
        testImplementation(gav("org.junit.jupiter.params"))
    }
}

// Add downloaded HAPI repo protobuf files into build directory and add to sources to build them
sourceSets {
    main {
        pbj {
            srcDir("hedera-protobufs/services")
            srcDir("hedera-protobufs/streams")
        }
        proto {
            srcDir("hedera-protobufs/services")
            srcDir("hedera-protobufs/streams")
        }
    }
}

// Give JUnit more ram and make it execute tests in parallel
tasks.withType<Test>().configureEach {
    // We are running a lot of tests 10s of thousands, so they need to run in parallel. Make each
    // class run in parallel.
    systemProperties["junit.jupiter.execution.parallel.enabled"] = true
    systemProperties["junit.jupiter.execution.parallel.mode.default"] = "concurrent"
    // limit amount of threads, so we do not use all CPU
    systemProperties["junit.jupiter.execution.parallel.config.dynamic.factor"] = "0.9"
    // us parallel GC to keep up with high temporary garbage creation,
    // and allow GC to use 40% of CPU if needed
    jvmArgs("-XX:+UseParallelGC", "-XX:GCTimeRatio=90")
    // Some also need more memory
    minHeapSize = "512m"
    maxHeapSize = "4096m"
}

tasks.withType<com.hedera.pbj.compiler.PbjCompilerTask> {
    doFirst {
        // Clean output directories before generating new code
        // TODO move this into
        // 'pbj-core/pbj-compiler/src/main/java/com/hedera/pbj/compiler/PbjCompilerTask.java'
        delete(javaMainOutputDirectory)
        delete(javaTestOutputDirectory)
    }
}
