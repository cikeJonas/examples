/**
 * Copyright 2016 Confluent Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.confluent.examples.streams;

import io.confluent.examples.streams.utils.GenericAvroSerde;
import io.confluent.examples.streams.utils.PriorityQueueSerde;
import io.confluent.examples.streams.utils.WindowedSerde;
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.KStreamBuilder;
import org.apache.kafka.streams.kstream.KTable;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.kstream.Windowed;

import java.io.File;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.Properties;

/**
 * Create a data feed of the top 100 news articles per industry, ranked by click-through-rate
 * (assuming this is for the past hour).
 * <p>
 * Note: The generic Avro binding is used for serialization/deserialization. This means the
 * appropriate Avro schema files must be provided for each of the "intermediate" Avro classes, i.e.
 * whenever new types of Avro objects (in the form of GenericRecord) are being passed between
 * processing steps.
 * <p>
 * Note: This example uses lambda expressions and thus works with Java 8+ only.
 */
public class TopArticlesLambdaExample {

    private static boolean isArticle(final GenericRecord record) {
        final String flags = (String) record.get("flags");

        return flags.contains("ART");
    }

    public static void main(final String[] args) throws Exception {
        final Properties streamsConfiguration = new Properties();
        // Give the Streams application a unique name.  The name must be unique in the Kafka cluster
        // against which the application is run.
        streamsConfiguration.put(StreamsConfig.APPLICATION_ID_CONFIG, "top-articles-lambda-example");
        // Where to find Kafka broker(s).
        streamsConfiguration.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
        // Where to find the corresponding ZooKeeper ensemble.
        streamsConfiguration.put(StreamsConfig.ZOOKEEPER_CONNECT_CONFIG, "localhost:2181");
        // Where to find the Confluent schema registry instance(s)
        streamsConfiguration.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, "http://localhost:8081");
        // Specify default (de)serializers for record keys and for record values.
        streamsConfiguration.put(StreamsConfig.KEY_SERDE_CLASS_CONFIG, Serdes.String().getClass().getName());
        streamsConfiguration.put(StreamsConfig.VALUE_SERDE_CLASS_CONFIG, GenericAvroSerde.class);

        final Serde<String> stringSerde = Serdes.String();
        final Serde<GenericRecord> avroSerde = new GenericAvroSerde();
        final Serde<Windowed<String>> windowedStringSerde = new WindowedSerde<>(stringSerde);

        final KStreamBuilder builder = new KStreamBuilder();

        final KStream<byte[], GenericRecord> views = builder.stream("PageViews");

        final KStream<GenericRecord, GenericRecord> articleViews = views
            // filter only article pages
            .filter((dummy, record) -> isArticle(record))
            // map <page id, industry> as key
            .map((dummy, article) -> new KeyValue<>(article, article));

        final Schema schema = new Schema.Parser().parse(new File("pageviewstats.avsc"));

        final KTable<Windowed<GenericRecord>, Long> viewCounts = articleViews
                // count the clicks per hour, using tumbling windows with a size of one hour
                .groupByKey(avroSerde, avroSerde)
                .count(TimeWindows.of(60 * 60 * 1000L), "PageViewCountStore");

        final KTable<Windowed<String>, PriorityQueue<GenericRecord>> allViewCounts = viewCounts
            .groupBy(
                // the selector
                (windowedArticle, count) -> {
                    // project on the industry field for key
                    Windowed<String> windowedIndustry =
                        new Windowed<>((String) windowedArticle.key().get("industry"), windowedArticle.window());
                    // add the page into the value
                    GenericRecord viewStats = new GenericData.Record(schema);
                    viewStats.put("page", "pageId");
                    viewStats.put("industry", "industryName");
                    viewStats.put("count", count);
                    return new KeyValue<>(windowedIndustry, viewStats);
                },
                windowedStringSerde,
                avroSerde
            ).aggregate(
                // the initializer
                () -> {
                    final Comparator<GenericRecord> comparator =
                        (o1, o2) -> (int) ((Long) o1.get("count") - (Long) o2.get("count"));
                    return new PriorityQueue<>(comparator);
                },

                // the "add" aggregator
                (windowedIndustry, record, queue) -> {
                    queue.add(record);
                    return queue;
                },

                // the "remove" aggregator
                (windowedIndustry, record, queue) -> {
                    queue.remove(record);
                    return queue;
                },

                new PriorityQueueSerde<>(),
                "AllArticles"
            );

        final int topN = 100;
        final KTable<Windowed<String>, String> topViewCounts = allViewCounts
            .mapValues(queue -> {
                final StringBuilder sb = new StringBuilder();
                for (int i = 0; i < topN; i++) {
                    final GenericRecord record = queue.poll();
                    if (record == null)
                        break;
                    sb.append((String) record.get("page"));
                    sb.append("\n");
                }
                return sb.toString();
            });

        topViewCounts.to(windowedStringSerde, stringSerde, "TopNewsPerIndustry");

        final KafkaStreams streams = new KafkaStreams(builder, streamsConfiguration);
        streams.start();

        // Add shutdown hook to respond to SIGTERM and gracefully close Kafka Streams
        Runtime.getRuntime().addShutdownHook(new Thread(streams::close));
    }

}
