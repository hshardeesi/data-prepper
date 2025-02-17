/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.dataprepper.plugins.kafka.producer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jsonschema.core.exceptions.ProcessingException;
import com.github.fge.jsonschema.core.report.ProcessingReport;
import com.github.fge.jsonschema.main.JsonSchema;
import com.github.fge.jsonschema.main.JsonSchemaFactory;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.opensearch.dataprepper.expression.ExpressionEvaluator;
import org.opensearch.dataprepper.model.event.Event;
import org.opensearch.dataprepper.model.event.EventHandle;
import org.opensearch.dataprepper.model.log.JacksonLog;
import org.opensearch.dataprepper.model.record.Record;
import org.opensearch.dataprepper.plugins.kafka.configuration.KafkaProducerConfig;
import org.opensearch.dataprepper.plugins.kafka.service.SchemaService;
import org.opensearch.dataprepper.plugins.kafka.sink.DLQSink;
import org.opensearch.dataprepper.plugins.kafka.util.MessageFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Future;


/**
 * * A helper class which helps takes the buffer data
 * and produce it to a given kafka topic
 */

public class KafkaCustomProducer<T> {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaCustomProducer.class);

    private final Producer<String, T> producer;

    private final KafkaProducerConfig kafkaProducerConfig;

    private final DLQSink dlqSink;

    private final Collection<EventHandle> bufferedEventHandles;

    private final ExpressionEvaluator expressionEvaluator;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String tagTargetKey;

    private final String topicName;

    private final String serdeFormat;

    private final SchemaService schemaService;


    public KafkaCustomProducer(final Producer producer,
                               final KafkaProducerConfig kafkaProducerConfig,
                               final DLQSink dlqSink,
                               final ExpressionEvaluator expressionEvaluator,
                               final String tagTargetKey
    ) {
        this.producer = producer;
        this.kafkaProducerConfig = kafkaProducerConfig;
        this.dlqSink = dlqSink;
        bufferedEventHandles = new LinkedList<>();
        this.expressionEvaluator = expressionEvaluator;
        this.tagTargetKey = tagTargetKey;
        this.topicName = ObjectUtils.isEmpty(kafkaProducerConfig.getTopic()) ? null : kafkaProducerConfig.getTopic().getName();
        this.serdeFormat = ObjectUtils.isEmpty(kafkaProducerConfig.getSerdeFormat()) ? null : kafkaProducerConfig.getSerdeFormat();
        schemaService = new SchemaService.SchemaServiceBuilder().getFetchSchemaService(topicName, kafkaProducerConfig.getSchemaConfig()).build();
    }

    public void produceRawData(final byte[] bytes, final String key) {
        try {
            send(topicName, key, bytes).get();
        } catch (Exception e) {
            LOG.error("Error occurred while publishing {}", e.getMessage());
        }
    }

    public void produceRecords(final Record<Event> record) {
        bufferedEventHandles.add(record.getData().getEventHandle());
        Event event = getEvent(record);
        final String key = event.formatString(kafkaProducerConfig.getPartitionKey(), expressionEvaluator);
        try {
            if (Objects.equals(serdeFormat, MessageFormat.JSON.toString())) {
                publishJsonMessage(record, key);
            } else if (Objects.equals(serdeFormat, MessageFormat.AVRO.toString())) {
                publishAvroMessage(record, key);
            } else {
                publishPlaintextMessage(record, key);
            }
        } catch (Exception e) {
            LOG.error("Error occurred while publishing {}", e.getMessage());
            releaseEventHandles(false);
        }

    }

    private Event getEvent(final Record<Event> record) {
        Event event = record.getData();
        try {
            event = addTagsToEvent(event, tagTargetKey);
        } catch (JsonProcessingException e) {
            LOG.error("error occurred while processing tag target key");
        }
        return event;
    }


    private void publishPlaintextMessage(final Record<Event> record, final String key) {
        send(topicName, key, record.getData().toJsonString());
    }

    private void publishAvroMessage(final Record<Event> record, final String key) {
        final Schema avroSchema = schemaService.getSchema(topicName);
        if (avroSchema == null) {
            throw new RuntimeException("Schema definition is mandatory in case of type avro");
        }
        final GenericRecord genericRecord = getGenericRecord(record.getData(), avroSchema);
        send(topicName, key, genericRecord);
    }

    private Future send(final String topicName, String key, final Object record) {
        if (Objects.isNull(key)) {
            return producer.send(new ProducerRecord(topicName, record), callBack(record));
        }

        return producer.send(new ProducerRecord(topicName, key, record), callBack(record));
    }

    private void publishJsonMessage(final Record<Event> record, final String key) throws IOException, ProcessingException {
        JsonNode dataNode = record.getData().getJsonNode();
        try {
            send(topicName, key, dataNode);
        }
        catch (Throwable ex) {
            dlqSink.perform(dataNode, ex);
        }
    }

    public boolean validateSchema(final String jsonData, final String schemaJson) throws IOException, ProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode schemaNode = objectMapper.readTree(schemaJson);
        JsonNode dataNode = objectMapper.readTree(jsonData);
        JsonSchemaFactory schemaFactory = JsonSchemaFactory.byDefault();
        JsonSchema schema = schemaFactory.getJsonSchema(schemaNode);
        ProcessingReport report = schema.validate(dataNode);
        return report != null ? report.isSuccess() : false;
    }

    private Callback callBack(final Object dataForDlq) {
        return (metadata, exception) -> {
            if (null != exception) {
                LOG.error("Error occured while publishing " + exception.getMessage());
                releaseEventHandles(false);
                dlqSink.perform(dataForDlq, exception);
            } else {
                releaseEventHandles(true);
            }
        };
    }


    private GenericRecord getGenericRecord(final Event event, final Schema schema) {
        final GenericRecord record = new GenericData.Record(schema);
        for (final String key : event.toMap().keySet()) {
            record.put(key, event.toMap().get(key));
        }
        return record;
    }

    private void releaseEventHandles(final boolean result) {
        for (final EventHandle eventHandle : bufferedEventHandles) {
            eventHandle.release(result);
        }
        bufferedEventHandles.clear();
    }

    private Event addTagsToEvent(final Event event, final String tagsTargetKey) throws JsonProcessingException {
        String eventJsonString = event.jsonBuilder().includeTags(tagsTargetKey).toJsonString();
        Map<String, Object> eventData = objectMapper.readValue(eventJsonString, new TypeReference<>() {
        });
        return JacksonLog.builder().withData(eventData).build();
    }

}
