package com.wanderly.analytics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Writes raw event files into the lake's raw zone, Hive-style partitioned so query engines
 * (Athena in AWS, DuckDB locally) can prune by date:
 * <pre>s3://wanderly-lake/raw/{topic}/dt=YYYY-MM-DD/hour=HH/part-{partition}-{firstOffset}.ndjson</pre>
 * The file name is derived from the Kafka partition and first offset, so a redelivered batch
 * overwrites the same object instead of creating a duplicate (idempotent writes).
 */
@Component
public class LakeWriter {

    private static final Logger log = LoggerFactory.getLogger(LakeWriter.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH").withZone(ZoneOffset.UTC);

    private final S3Client s3;
    private final String bucket;
    private volatile boolean bucketReady;

    public LakeWriter(S3Client s3, @Value("${wanderly.analytics.lake.bucket}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    public String bucket() {
        return bucket;
    }

    static String objectKey(String topic, int partition, long firstOffset, Instant firstTimestamp) {
        return "raw/%s/dt=%s/hour=%s/part-%d-%019d.ndjson".formatted(topic, DAY.format(firstTimestamp),
                HOUR.format(firstTimestamp), partition, firstOffset);
    }

    public void write(String key, String ndjson) {
        ensureBucket();
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("application/x-ndjson").build(),
                RequestBody.fromString(ndjson, StandardCharsets.UTF_8));
    }

    private void ensureBucket() {
        if (bucketReady) {
            return;
        }
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
            log.info("Created analytics lake bucket {}", bucket);
        }
        bucketReady = true;
    }
}
