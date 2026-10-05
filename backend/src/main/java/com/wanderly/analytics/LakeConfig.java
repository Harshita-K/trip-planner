package com.wanderly.analytics;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

import java.net.URI;

/**
 * S3 client for the analytics lake. Locally it points at SeaweedFS (path-style URLs, dummy keys);
 * with {@code LAKE_ENDPOINT} empty it's a normal AWS S3 client using the default credential chain
 * (IAM role on ECS), so the same code runs against real S3 in production.
 */
@Configuration
public class LakeConfig {

    @Bean(destroyMethod = "close")
    S3Client lakeS3(@Value("${wanderly.analytics.lake.endpoint:}") String endpoint,
                    @Value("${wanderly.analytics.lake.region}") String region,
                    @Value("${wanderly.analytics.lake.access-key}") String accessKey,
                    @Value("${wanderly.analytics.lake.secret-key}") String secretKey) {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(region))
                .httpClient(UrlConnectionHttpClient.create())
                // SDK >= 2.30 adds CRC checksums with aws-chunked uploads by default; many S3-compatible
                // stores (SeaweedFS, MinIO, older Ceph) reject them. Only send checksums when required.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        if (endpoint.isBlank()) {
            return builder.credentialsProvider(DefaultCredentialsProvider.create()).build();
        }
        return builder.endpointOverride(URI.create(endpoint))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }
}
