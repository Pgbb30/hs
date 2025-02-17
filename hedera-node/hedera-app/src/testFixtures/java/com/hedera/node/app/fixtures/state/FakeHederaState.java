/*
 * Copyright (C) 2023 Hedera Hashgraph, LLC
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

package com.hedera.node.app.fixtures.state;

import com.hedera.node.app.spi.fixtures.state.ListReadableQueueState;
import com.hedera.node.app.spi.fixtures.state.ListWritableQueueState;
import com.hedera.node.app.spi.fixtures.state.MapReadableKVState;
import com.hedera.node.app.spi.fixtures.state.MapReadableStates;
import com.hedera.node.app.spi.fixtures.state.MapWritableKVState;
import com.hedera.node.app.spi.fixtures.state.MapWritableStates;
import com.hedera.node.app.spi.state.ReadableKVState;
import com.hedera.node.app.spi.state.ReadableSingletonStateBase;
import com.hedera.node.app.spi.state.ReadableStates;
import com.hedera.node.app.spi.state.WritableSingletonStateBase;
import com.hedera.node.app.spi.state.WritableStates;
import com.hedera.node.app.state.HederaState;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;

/** A useful test double for {@link HederaState}. Works together with {@link MapReadableStates} and other fixtures. */
public class FakeHederaState implements HederaState {
    // Key is Service, value is Map of state name to HashMap or List or Object (depending on state type)
    private final Map<String, Map<String, Object>> states = new HashMap<>();

    /** Adds to the service with the given name the {@link ReadableKVState} {@code states} */
    public void addService(@NonNull final String serviceName, @NonNull final Map<String, ?> dataSources) {
        var serviceStates = this.states.computeIfAbsent(serviceName, k -> new HashMap<>());
        serviceStates.putAll(dataSources);
    }

    @NonNull
    @Override
    public ReadableStates createReadableStates(@NonNull String serviceName) {
        final var serviceStates = states.get(serviceName);
        final var data = new HashMap<String, Object>();
        for (final var entry : serviceStates.entrySet()) {
            final var stateName = entry.getKey();
            final var state = entry.getValue();
            if (state instanceof Queue queue) {
                data.put(stateName, new ListReadableQueueState(stateName, queue));
            } else if (state instanceof Map map) {
                data.put(stateName, new MapReadableKVState(stateName, map));
            } else if (state instanceof AtomicReference ref) {
                data.put(stateName, new ReadableSingletonStateBase(stateName, ref::get));
            }
        }
        return new MapReadableStates(data);
    }

    @NonNull
    @Override
    public WritableStates createWritableStates(@NonNull String serviceName) {
        final var serviceStates = states.get(serviceName);
        final var data = new HashMap<String, Object>();
        for (final var entry : serviceStates.entrySet()) {
            final var stateName = entry.getKey();
            final var state = entry.getValue();
            if (state instanceof Queue queue) {
                data.put(stateName, new ListWritableQueueState(stateName, queue));
            } else if (state instanceof Map map) {
                data.put(stateName, new MapWritableKVState(stateName, map));
            } else if (state instanceof AtomicReference ref) {
                data.put(stateName, new WritableSingletonStateBase(stateName, ref::get, ref::set));
            }
        }
        return new MapWritableStates(data);
    }
}
