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

package com.swirlds.common.internal;

import com.swirlds.common.system.address.AddressBook;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Temporary internal only class to facilitate an incremental refactor of the {@code com.swirlds.platform.Browser} class.
 * Will not be providing javadoc on class members due to ephemeral nature of this temporary class.
 */
public class ApplicationDefinition {

    private final String swirldName;
    private final String[] appParameters;
    private final String appJarFileName;
    private final String mainClassName;
    private final Path appJarPath;
    private final AddressBook addressBook;

    private byte[] masterKey;
    private byte[] swirldId;

    public ApplicationDefinition(
            @NonNull final String swirldName,
            @NonNull final String[] appParameters,
            @NonNull final String appJarFileName,
            @NonNull final String mainClassName,
            @NonNull final Path appJarPath,
            @NonNull final AddressBook addressBook) {
        this.swirldName = Objects.requireNonNull(swirldName, "swirldName must not be null");
        this.appParameters = Objects.requireNonNull(appParameters, "appParameters must not be null");
        this.appJarFileName = Objects.requireNonNull(appJarFileName, "appJarFileName must not be null");
        this.mainClassName = Objects.requireNonNull(mainClassName, "mainClassName must not be null");
        this.appJarPath = Objects.requireNonNull(appJarPath, "appJarPath must not be null");
        this.addressBook = Objects.requireNonNull(addressBook, "addressBook must not be null");
    }

    public String getSwirldName() {
        return swirldName;
    }

    public String[] getAppParameters() {
        return appParameters;
    }

    public String getAppJarFileName() {
        return appJarFileName;
    }

    public String getMainClassName() {
        return mainClassName;
    }

    public String getApplicationName() {
        return mainClassName.substring(0, mainClassName.length() - 4);
    }

    public Path getAppJarPath() {
        return appJarPath;
    }

    public AddressBook getAddressBook() {
        return addressBook;
    }

    public byte[] getMasterKey() {
        return masterKey;
    }

    public byte[] getSwirldId() {
        return swirldId;
    }

    public void setMasterKey(final byte[] masterKey) {
        this.masterKey = masterKey;
    }

    public void setSwirldId(final byte[] swirldId) {
        this.swirldId = swirldId;
    }
}
