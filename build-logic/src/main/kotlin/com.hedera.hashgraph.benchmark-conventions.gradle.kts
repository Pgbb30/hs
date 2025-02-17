/*
 * Copyright (C) 2022 Hedera Hashgraph, LLC
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
    id("me.champeau.jmh")
}

val includesRegex: String by project
jmh {
    jmhVersion.set("1.36")
    includes.set(listOf(includesRegex))
}

tasks.jmh {
    outputs.upToDateWhen { false }
}

tasks.jmhJar {
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
    fileMode = 664
    dirMode = 775
    manifest(Action {
        attributes(mapOf("Multi-Release" to true))
    })
}
