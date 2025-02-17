/*
 * Copyright (C) 2018-2023 Hedera Hashgraph, LLC
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

package com.swirlds.platform.state.address;

import com.swirlds.common.system.address.Address;
import com.swirlds.common.system.address.AddressBook;
import com.swirlds.platform.network.Network;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Objects;

/**
 * {@link AddressBook AddressBook} utility methods.
 */
public final class AddressBookNetworkUtils {

    private AddressBookNetworkUtils() {}

    /**
     * Check if the address is local to the machine.
     *
     * @param address the address to check
     * @return true if the address is local to the machine, false otherwise
     * @throws IllegalStateException if the locality of the address cannot be determined.
     */
    public static boolean isLocal(@NonNull final Address address) {
        Objects.requireNonNull(address, "The address must not be null.");
        try {
            return Network.isOwn(InetAddress.getByName(address.getHostnameInternal()));
        } catch (final SocketException | UnknownHostException e) {
            throw new IllegalStateException(
                    "Not able to determine locality of address [%s] for node [%s]"
                            .formatted(address.getHostnameInternal(), address.getNodeId()),
                    e);
        }
    }

    /**
     * Get the number of addresses currently in the address book that are running on this computer. When the browser is
     * run with a config.txt file, it can launch multiple copies of the app simultaneously, each with its own TCP/IP
     * port. This method returns how many there are.
     *
     * @param addressBook the address book to check
     * @return the number of local addresses
     */
    public static int getLocalAddressCount(@NonNull final AddressBook addressBook) {
        Objects.requireNonNull(addressBook, "The addressBook must not be null.");
        int count = 0;
        for (final Address address : addressBook) {
            if (isLocal(address)) {
                count++;
            }
        }
        return count;
    }
}
