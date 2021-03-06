package com.deviantart.kafka_connect_s3;

import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.S3ClientOptions;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.util.IOUtils;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.IntegerSerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.joda.time.Instant;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.zip.GZIPInputStream;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertThat;

public class ConnectorIT {

    private static final String TEST_TOPIC_NAME = "test-topic";
    private static final String BUCKET_NAME = "fakes3";
    private static final String TODAY_FORMATTED =
            new SimpleDateFormat("YYYY-MM-dd").format(Instant.now().toDate());
    private static final String BUCKET_PREFIX = "connect-system-test/";
    private static final String FILE_PREFIX = "systest/";
    private static final String FILE_PREFIX_WITH_DATE = FILE_PREFIX + TODAY_FORMATTED + "/";
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static KafkaProducer<Integer, String> producer;
    private static List<ProducerRecord<Integer, String>> messages;
    private static List<String> expectedMessagesInS3PerPartition = Arrays.asList("", "", "");
    private static AmazonS3Client s3Client;

    @BeforeClass
    public static void oneTimeSetUp() {

        String kafkaBrokers = System.getenv("KAFKA_BROKERS");
        String fakeS3Endpoint = "http://localhost:4569";

        Properties producerProperties = new Properties();
        producerProperties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBrokers);
        producerProperties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, IntegerSerializer.class);
        producerProperties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        producerProperties.put("producer.type", "async");
        producerProperties.put(ProducerConfig.ACKS_CONFIG, "1");
        producerProperties.put(ProducerConfig.LINGER_MS_CONFIG, "5");

        producer = new KafkaProducer<>(producerProperties);

        messages = new ArrayList<>(100);

        for (int i = 200; i < 300; i++) {
            int partition = i % 3;
            String message = "{\"foo\": \"bar\", \"counter\":" + i + "}";

            String existingMessagesInS3PerPartition = expectedMessagesInS3PerPartition.get(partition);
            existingMessagesInS3PerPartition += message + "\n";
            expectedMessagesInS3PerPartition.set(partition, existingMessagesInS3PerPartition);

            messages.add(
                new ProducerRecord<>(TEST_TOPIC_NAME, partition, i, message)
            );
        }

        BasicAWSCredentials credentials = new BasicAWSCredentials("foo", "bar");
        s3Client = new AmazonS3Client(credentials);
        s3Client.setEndpoint(fakeS3Endpoint);
        s3Client.setS3ClientOptions(new S3ClientOptions().withPathStyleAccess(true));
    }

    @Test
    public void connectorShouldSaveFileInS3() throws InterruptedException, ExecutionException, IOException {

        Iterator<ProducerRecord<Integer, String>> messagesIter = messages.iterator();
        while (messagesIter.hasNext()) {
            producer.send(messagesIter.next()).get();
        }

        Thread.sleep(60_000L);

        /*
         * Asserting messages saved from partition 0
         */

        assertS3FileContents(
                BUCKET_PREFIX + FILE_PREFIX + "last_chunk_index.test-topic-00000.txt",
                FILE_PREFIX_WITH_DATE + "test-topic-00000-000000000000.index.json",
                false,
                UTF8
        );

        assertS3FileContents(
                BUCKET_PREFIX + FILE_PREFIX_WITH_DATE + "test-topic-00000-000000000000.index.json",
                "{\"chunks\":[{\"byte_length_uncompressed\":990,\"num_records\":33,\"byte_length\":137,\"byte_offset\":0,\"first_record_offset\":0}]}",
                false,
                UTF8
        );

        assertS3FileContents(
                BUCKET_PREFIX + FILE_PREFIX_WITH_DATE + "test-topic-00000-000000000000.gz",
                expectedMessagesInS3PerPartition.get(0),
                true,
                UTF8
        );

        /*
         * Asserting messages saved from partition 1
         */

        assertS3FileContents(
       BUCKET_PREFIX + FILE_PREFIX + "last_chunk_index.test-topic-00001.txt",
    FILE_PREFIX_WITH_DATE + "test-topic-00001-000000000000.index.json",
   false,
            UTF8
        );

        assertS3FileContents(
       BUCKET_PREFIX + FILE_PREFIX_WITH_DATE + "test-topic-00001-000000000000.index.json",
    "{\"chunks\":[{\"byte_length_uncompressed\":990,\"num_records\":33,\"byte_length\":137,\"byte_offset\":0,\"first_record_offset\":0}]}",
   false,
            UTF8
        );

        assertS3FileContents(
                BUCKET_PREFIX + FILE_PREFIX_WITH_DATE + "test-topic-00001-000000000000.gz",
                expectedMessagesInS3PerPartition.get(1),
                true,
                UTF8
        );

        /*
         * Asserting messages saved from partition 2
         */

        assertS3FileContents(
                BUCKET_PREFIX + FILE_PREFIX + "last_chunk_index.test-topic-00002.txt",
                FILE_PREFIX_WITH_DATE + "test-topic-00002-000000000000.index.json",
                false,
                UTF8
        );

        assertS3FileContents(
                BUCKET_PREFIX + FILE_PREFIX_WITH_DATE + "test-topic-00002-000000000000.index.json",
                "{\"chunks\":[{\"byte_length_uncompressed\":1020,\"num_records\":34,\"byte_length\":139,\"byte_offset\":0,\"first_record_offset\":0}]}",
                false,
                UTF8
        );

        assertS3FileContents(
                BUCKET_PREFIX + FILE_PREFIX_WITH_DATE + "test-topic-00002-000000000000.gz",
                expectedMessagesInS3PerPartition.get(2),
                true,
                UTF8
        );
    }

    private void assertS3FileContents(String key, String content, boolean gzipped, Charset encoding) throws IOException {
        S3Object s3Object = s3Client.getObject(new GetObjectRequest(BUCKET_NAME, key));
        InputStream objectInputStream = s3Object.getObjectContent();
        String objectContent = null;

        if (gzipped) {
            byte[] res = decompressGzipContent(objectInputStream);
            objectContent = new String(res);
        } else {
            objectContent = IOUtils.toString(objectInputStream);
        }

        s3Object.close();

        assertThat(objectContent, is(content));
    }

    private byte[] decompressGzipContent(InputStream is){
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try{
            IOUtils.copy(new GZIPInputStream(is), out);
        } catch(IOException e){
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }
}
