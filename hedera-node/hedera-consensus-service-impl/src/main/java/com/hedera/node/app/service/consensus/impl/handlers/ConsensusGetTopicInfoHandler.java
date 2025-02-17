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

package com.hedera.node.app.service.consensus.impl.handlers;

import static com.hedera.hapi.node.base.ResponseCodeEnum.INVALID_TOPIC_ID;
import static com.hedera.hapi.node.base.ResponseCodeEnum.OK;
import static com.hedera.hapi.node.base.ResponseType.ANSWER_ONLY;
import static com.hedera.hapi.node.base.ResponseType.ANSWER_STATE_PROOF;
import static com.hedera.hapi.node.base.ResponseType.COST_ANSWER;
import static com.hedera.node.app.spi.key.KeyUtils.isEmpty;
import static com.hedera.node.app.spi.validation.Validations.mustExist;
import static java.util.Objects.requireNonNull;

import com.hedera.hapi.node.base.Duration;
import com.hedera.hapi.node.base.HederaFunctionality;
import com.hedera.hapi.node.base.QueryHeader;
import com.hedera.hapi.node.base.ResponseHeader;
import com.hedera.hapi.node.base.ResponseType;
import com.hedera.hapi.node.base.Timestamp;
import com.hedera.hapi.node.base.TopicID;
import com.hedera.hapi.node.consensus.ConsensusGetTopicInfoQuery;
import com.hedera.hapi.node.consensus.ConsensusGetTopicInfoResponse;
import com.hedera.hapi.node.consensus.ConsensusTopicInfo;
import com.hedera.hapi.node.transaction.Query;
import com.hedera.hapi.node.transaction.Response;
import com.hedera.node.app.service.consensus.ReadableTopicStore;
import com.hedera.node.app.spi.workflows.PaidQueryHandler;
import com.hedera.node.app.spi.workflows.PreCheckException;
import com.hedera.node.app.spi.workflows.QueryContext;
import com.hedera.node.config.data.LedgerConfig;
import edu.umd.cs.findbugs.annotations.NonNull;
import java.util.Optional;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * This class contains all workflow-related functionality regarding {@link HederaFunctionality#CONSENSUS_GET_TOPIC_INFO}.
 */
@Singleton
public class ConsensusGetTopicInfoHandler extends PaidQueryHandler {

    @Inject
    public ConsensusGetTopicInfoHandler() {}

    @Override
    public QueryHeader extractHeader(@NonNull final Query query) {
        requireNonNull(query);
        return query.consensusGetTopicInfoOrThrow().header();
    }

    @Override
    public Response createEmptyResponse(@NonNull final ResponseHeader header) {
        requireNonNull(header);
        final var response = ConsensusGetTopicInfoResponse.newBuilder().header(header);
        return Response.newBuilder().consensusGetTopicInfo(response).build();
    }

    @Override
    public boolean requiresNodePayment(@NonNull ResponseType responseType) {
        return responseType == ANSWER_ONLY || responseType == ANSWER_STATE_PROOF;
    }

    @Override
    public boolean needsAnswerOnlyCost(@NonNull ResponseType responseType) {
        return COST_ANSWER == responseType;
    }

    @Override
    public void validate(@NonNull final QueryContext context) throws PreCheckException {
        requireNonNull(context);
        final var query = context.query();
        final var topicStore = context.createStore(ReadableTopicStore.class);
        final ConsensusGetTopicInfoQuery op = query.consensusGetTopicInfoOrThrow();
        if (op.hasTopicID()) {
            // The topic must exist
            final var topic = topicStore.getTopic(op.topicID());
            mustExist(topic, INVALID_TOPIC_ID);
            if (topic.deleted()) {
                throw new PreCheckException(INVALID_TOPIC_ID);
            }
        } else {
            throw new PreCheckException(INVALID_TOPIC_ID);
        }
    }

    @Override
    public Response findResponse(@NonNull final QueryContext context, @NonNull final ResponseHeader header) {
        requireNonNull(context);
        requireNonNull(header);
        final var query = context.query();
        final var config = context.configuration().getConfigData(LedgerConfig.class);
        final var topicStore = context.createStore(ReadableTopicStore.class);
        final var op = query.consensusGetTopicInfoOrThrow();
        final var response = ConsensusGetTopicInfoResponse.newBuilder();
        final var topic = op.topicIDOrElse(TopicID.DEFAULT);
        response.topicID(topic);

        final var responseType = op.headerOrElse(QueryHeader.DEFAULT).responseType();
        response.header(header);
        if (header.nodeTransactionPrecheckCode() == OK && responseType != COST_ANSWER) {
            final var optionalInfo = infoForTopic(topic, topicStore, config);
            optionalInfo.ifPresent(response::topicInfo);
        }

        return Response.newBuilder().consensusGetTopicInfo(response).build();
    }

    /**
     * Provides information about a topic.
     * @param topicID the topic to get information about
     * @param topicStore the topic store
     * @param config the LedgerConfig
     * @return the information about the topic
     */
    private Optional<ConsensusTopicInfo> infoForTopic(
            @NonNull final TopicID topicID,
            @NonNull final ReadableTopicStore topicStore,
            @NonNull final LedgerConfig config) {
        final var meta = topicStore.getTopic(topicID);
        if (meta == null) {
            return Optional.empty();
        } else {
            final var info = ConsensusTopicInfo.newBuilder();
            info.memo(meta.memo());
            info.runningHash(meta.runningHash());
            info.sequenceNumber(meta.sequenceNumber());
            info.expirationTime(Timestamp.newBuilder().seconds(meta.expiry()).build());
            if (!isEmpty(meta.adminKey())) info.adminKey(meta.adminKey());
            if (!isEmpty(meta.submitKey())) info.submitKey(meta.submitKey());
            info.autoRenewPeriod(Duration.newBuilder().seconds(meta.autoRenewPeriod()));
            if (meta.hasAutoRenewAccountId()) info.autoRenewAccount(meta.autoRenewAccountId());

            info.ledgerId(config.id());
            return Optional.of(info.build());
        }
    }
}
