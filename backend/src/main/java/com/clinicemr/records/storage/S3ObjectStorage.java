package com.clinicemr.records.storage;

import java.net.URL;
import java.util.Optional;

import com.clinicemr.shared.security.SecurityProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

@Component
@ConditionalOnProperty(prefix = "app.storage", name = "type", havingValue = "s3", matchIfMissing = true)
public class S3ObjectStorage implements ObjectStorage {

    private final StorageProperties props;
    private final S3Client client;
    private final S3Presigner presigner;
    private final SignedFileLinks links;

    public S3ObjectStorage(StorageProperties props, SecurityProperties security) {
        this.props = props;
        this.links = new SignedFileLinks(props, security);
        var credentials = StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
        var s3Config = S3Configuration.builder().pathStyleAccessEnabled(props.pathStyle()).build();
        Region region = Region.of(props.region());

        // Newer SDKs add CRC checksums by default; Oracle and older MinIO reject them, so only send when required
        this.client = S3Client.builder()
                .endpointOverride(props.endpoint())
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(props.endpoint())
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .build();
    }

    @Override
    public URL presignUpload(String key, String contentType) {
        PutObjectRequest put = PutObjectRequest.builder()
                .bucket(props.bucket())
                .key(key)
                .contentType(contentType)
                .build();
        return presigner.presignPutObject(b -> b.signatureDuration(props.presignTtl()).putObjectRequest(put)).url();
    }

    @Override
    public URL presignDownload(String key) {
        if (props.proxyDownloads()) {
            return links.create(key);
        }
        GetObjectRequest get = GetObjectRequest.builder().bucket(props.bucket()).key(key).build();
        return presigner.presignGetObject(b -> b.signatureDuration(props.presignTtl()).getObjectRequest(get)).url();
    }

    @Override
    public void put(String key, String contentType, byte[] bytes) {
        client.putObject(PutObjectRequest.builder().bucket(props.bucket()).key(key).contentType(contentType).build(),
                RequestBody.fromBytes(bytes));
    }

    @Override
    public Optional<byte[]> read(String key) {
        try {
            return Optional.of(client.getObjectAsBytes(GetObjectRequest.builder().bucket(props.bucket()).key(key).build())
                    .asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public Optional<Long> sizeOf(String key) {
        try {
            return Optional.of(client.headObject(HeadObjectRequest.builder().bucket(props.bucket()).key(key).build())
                    .contentLength());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    @Override
    public void delete(String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(props.bucket()).key(key).build());
    }

    @PreDestroy
    void close() {
        presigner.close();
        client.close();
    }
}
