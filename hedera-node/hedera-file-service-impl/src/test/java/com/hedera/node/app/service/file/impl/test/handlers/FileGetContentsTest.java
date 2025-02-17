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

package com.hedera.node.app.service.file.impl.test.handlers;

import static com.hedera.node.app.spi.fixtures.workflows.ExceptionConditions.responseCode;
import static com.hedera.test.factories.scenarios.TxnHandlingScenario.COMPLEX_KEY_ACCOUNT_KT;
import static com.hedera.test.utils.TxnUtils.payerSponsoredPbjTransfer;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;

import com.hedera.hapi.node.base.FileID;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseCodeEnum;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.file.FileContents;
import com.hedera.hapi.node.file.FileGetContentsQuery;
import com.hedera.hapi.node.file.FileGetContentsResponse;
import com.hedera.hapi.node.state.file.File;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.service.file.ReadableFileStore;
import com.hedera.node.app.service.file.impl.ReadableFileStoreImpl;
import com.hedera.node.app.service.file.impl.handlers.FileGetContentsHandler;
import com.hedera.node.app.service.file.impl.test.FileTestBase;
import com.hedera.node.app.spi.fixtures.state.MapReadableKVState;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.pbj.runtime.io.buffer.Bytes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FileGetContentsTest extends FileTestBase {

    @Mock
    private QueryContext context;

    private FileGetContentsHandler subject;

    @BeforeEach
    void setUp() {
        subject = new FileGetContentsHandler();
    }

    @Test
    void extractsHeader() {
        final var query = createGetFileContentQuery(fileId.fileNum());
        final var header = subject.extractHeader(query);
        final var op = query.fileGetContentsOrThrow();
        assertEquals(op.header(), header);
    }

    @Test
    void createsEmptyResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.FAIL_FEE)
                .build();
        final var response = subject.createEmptyResponse(responseHeader);
        final var expectedResponse = Response.newBuilder()
                .fileGetContents(FileGetContentsResponse.newBuilder().header(responseHeader))
                .build();
        assertEquals(expectedResponse, response);
    }

    @Test
    void requiresPayment() {
        assertTrue(subject.requiresNodePayment(ResponseType.ANSWER_ONLY));
        assertTrue(subject.requiresNodePayment(ResponseType.ANSWER_STATE_PROOF));
        assertFalse(subject.requiresNodePayment(ResponseType.COST_ANSWER));
        assertFalse(subject.requiresNodePayment(ResponseType.COST_ANSWER_STATE_PROOF));
    }

    @Test
    void needsAnswerOnlyCostForCostAnswer() {
        assertFalse(subject.needsAnswerOnlyCost(ResponseType.ANSWER_ONLY));
        assertFalse(subject.needsAnswerOnlyCost(ResponseType.ANSWER_STATE_PROOF));
        assertTrue(subject.needsAnswerOnlyCost(ResponseType.COST_ANSWER));
        assertFalse(subject.needsAnswerOnlyCost(ResponseType.COST_ANSWER_STATE_PROOF));
    }

    @Test
    void validatesQueryWhenValidFile() throws Throwable {
        givenValidFile();

        final var query = createGetFileContentQuery(fileId.fileNum());
        given(context.query()).willReturn(query);
        given(context.createStore(ReadableFileStore.class)).willReturn(readableStore);

        assertThatCode(() -> subject.validate(context)).doesNotThrowAnyException();
    }

    @Test
    void validatesQueryIfInvalidFile() throws Throwable {
        readableFileState.reset();
        final var state = MapReadableKVState.<Long, File>builder("FILES").build();
        given(readableStates.<Long, File>get(FILES)).willReturn(state);
        final var store = new ReadableFileStoreImpl(readableStates);

        final var query = createGetFileContentQuery(fileId.fileNum());
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableFileStore.class)).thenReturn(store);

        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.INVALID_FILE_ID));
    }

    @Test
    void validatesQueryIfDeletedFile() throws Throwable {
        givenValidFile(true);
        readableFileState = readableFileState();
        given(readableStates.<FileID, File>get(FILES)).willReturn(readableFileState);
        readableStore = new ReadableFileStoreImpl(readableStates);

        final var query = createGetFileContentQuery(fileId.fileNum());
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableFileStore.class)).thenReturn(readableStore);

        assertThatThrownBy(() -> subject.validate(context))
                .isInstanceOf(PreCheckException.class)
                .has(responseCode(ResponseCodeEnum.FILE_DELETED));
    }

    @Test
    void getsResponseIfFailedResponse() {
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.FAIL_FEE)
                .build();

        final var query = createGetFileContentQuery(fileId.fileNum());
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableFileStore.class)).thenReturn(readableStore);

        final var response = subject.findResponse(context, responseHeader);
        final var op = response.fileGetContentsOrThrow();
        assertEquals(ResponseCodeEnum.FAIL_FEE, op.header().nodeTransactionPrecheckCode());
        assertNull(op.fileContents());
    }

    @Test
    void getsResponseIfOkResponse() {
        givenValidFile();
        final var responseHeader = ResponseHeader.newBuilder()
                .nodeTransactionPrecheckCode(ResponseCodeEnum.OK)
                .build();
        final var expectedContent = getExpectedContent();

        final var query = createGetFileContentQuery(fileId.fileNum());
        when(context.query()).thenReturn(query);
        when(context.createStore(ReadableFileStore.class)).thenReturn(readableStore);

        final var response = subject.findResponse(context, responseHeader);
        final var fileContentResponse = response.fileGetContentsOrThrow();
        assertEquals(ResponseCodeEnum.OK, fileContentResponse.header().nodeTransactionPrecheckCode());
        assertEquals(expectedContent, fileContentResponse.fileContents());
    }

    private FileContents getExpectedContent() {
        return FileContents.newBuilder()
                .contents(Bytes.wrap(contents))
                .fileID(fileId)
                .build();
    }

    private Query createGetFileContentQuery(final long fileId) {
        final var payment =
                payerSponsoredPbjTransfer(payerIdLiteral, COMPLEX_KEY_ACCOUNT_KT, beneficiaryIdStr, paymentAmount);
        final var data = FileGetContentsQuery.newBuilder()
                .fileID(FileID.newBuilder().fileNum(fileId).build())
                .header(QueryHeader.newBuilder().payment(payment).build())
                .build();

        return Query.newBuilder().fileGetContents(data).build();
    }
}
