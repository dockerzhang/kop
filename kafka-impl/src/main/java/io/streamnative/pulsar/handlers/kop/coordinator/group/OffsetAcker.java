/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.streamnative.pulsar.handlers.kop.coordinator.group;

import io.streamnative.pulsar.handlers.kop.offset.OffsetAndMetadata;
import io.streamnative.pulsar.handlers.kop.utils.KopTopic;
import io.streamnative.pulsar.handlers.kop.utils.MessageIdUtils;
import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.internals.ConsumerProtocol;
import org.apache.kafka.clients.consumer.internals.PartitionAssignor.Assignment;
import org.apache.kafka.common.TopicPartition;
import org.apache.pulsar.client.api.Consumer;
import org.apache.pulsar.client.api.ConsumerBuilder;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.SubscriptionInitialPosition;
import org.apache.pulsar.client.api.SubscriptionType;
import org.apache.pulsar.client.impl.PulsarClientImpl;

/**
 * This class used to track all the partition offset commit position.
 */
@Slf4j
public class OffsetAcker implements Closeable {

    private final ConsumerBuilder<byte[]> consumerBuilder;

    public OffsetAcker(PulsarClientImpl pulsarClient) {
        this.consumerBuilder = pulsarClient.newConsumer()
                .receiverQueueSize(0)
                .subscriptionInitialPosition(SubscriptionInitialPosition.Earliest)
                .subscriptionType(SubscriptionType.Shared);
    }

    // map off consumser: <groupId, consumers>
    Map<String, Map<String, Consumer<byte[]>>> consumers = new ConcurrentHashMap<>();

    public void addOffsetsTracker(String groupId, byte[] assignment) {
        ByteBuffer assignBuffer = ByteBuffer.wrap(assignment);
        Assignment assign = ConsumerProtocol.deserializeAssignment(assignBuffer);
        if (log.isDebugEnabled()) {
            log.debug(" Add offsets after sync group: {}", assign.toString());
        }
    }

    public void ackOffsets(String groupId, Map<TopicPartition, OffsetAndMetadata> offsetMetadata) {
        if (log.isDebugEnabled()) {
            log.debug(" ack offsets after commit offset for group: {}", groupId);
            offsetMetadata.forEach((partition, metadata) ->
                log.debug("\t partition: {}, offset: {}",
                    partition,  MessageIdUtils.getPosition(metadata.offset())));
        }
        offsetMetadata.forEach(((topicPartition, offsetAndMetadata) -> {
            // 1. get consumer, then do ackCumulative
            String topicName = KopTopic.toString(topicPartition);
            Consumer<byte[]> consumer = getConsumer(groupId, topicName);
            MessageId messageId = MessageIdUtils.getMessageId(offsetAndMetadata.offset());
            consumer.acknowledgeCumulativeAsync(messageId);
        }));
    }

    public void close(Set<String> groupIds) {
        groupIds.forEach(groupId -> consumers.get(groupId).values().forEach(consumer -> {
                try {
                    consumer.close();
                } catch (Exception e) {
                    log.warn("Error when close consumer topic: {}, sub: {}.",
                        consumer.getTopic(), consumer.getSubscription(), e);
                }
        }));
    }

    @Override
    public void close() {
        log.info("close OffsetAcker with {} groupIds", consumers.size());
        close(consumers.keySet());
    }

    public Consumer<byte[]> getConsumer(String groupId, String topicName) {
        Map<String, Consumer<byte[]>> group = consumers
            .computeIfAbsent(groupId, gid -> new ConcurrentHashMap<>());
        return group.computeIfAbsent(
                topicName,
            partition -> createConsumer(groupId, partition));
    }

    private Consumer<byte[]> createConsumer(String groupId, String topicName) {
        Consumer<byte[]> consumer = null;
        try {
            consumer = consumerBuilder.clone()
                    .topic(topicName)
                    .subscriptionName(groupId)
                    .subscribe();
        } catch (PulsarClientException e) {
            log.error("create consumer error", e);
        }
        return consumer;
    }

}
