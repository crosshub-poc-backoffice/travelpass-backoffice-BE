package com.crosshub.backoffice.storage;

import java.net.URI;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 파일 저장소. 올린 뒤 공개 URL 을 돌려준다 — DB 에는 그 안의 경로(key)만 넣는다 (StoredFiles). FE lib/storage.ts 를 옮긴 것 — 저장소 키는 BE 에만 둔다.
 * S3 프로토콜로 올리므로 로컬(RustFS, infra/local/docker-compose.yml)·Supabase Storage·NCP Object Storage·AWS S3 모두 같은 코드로 붙는다.
 *
 * 설정(app.storage.*)은 application.yml 참고. 키 이름은 FE 에서 쓰던 S3_* 그대로다.
 * public-url 은 공개 URL 앞부분 — Supabase 는 업로드 주소와 달라서 꼭 넣는다. 비우면 endpoint/bucket.
 */
@Component
public class ObjectStorage {

    private static final Logger log = LoggerFactory.getLogger(ObjectStorage.class);

    private final S3Client s3;
    private final String bucket;
    private final String publicUrl;
    private volatile boolean bucketReady = false;

    public ObjectStorage(@Value("${app.storage.endpoint}") String endpoint,
                         @Value("${app.storage.region}") String region,
                         @Value("${app.storage.bucket}") String bucket,
                         @Value("${app.storage.access-key}") String accessKey,
                         @Value("${app.storage.secret-key}") String secretKey,
                         @Value("${app.storage.public-url:}") String publicUrl) {
        this.bucket = bucket;
        this.publicUrl = (publicUrl == null || publicUrl.isBlank() ? endpoint + "/" + bucket : publicUrl).replaceAll("/$", "");
        this.s3 = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .forcePathStyle(true) // RustFS·Supabase 는 경로 방식(host/bucket/key)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .httpClient(UrlConnectionHttpClient.create())
                .build();
        StoredFiles.configure(this.publicUrl);
    }

    public String putProductImage(byte[] bytes, String mime) {
        return put("products", FileTypes.IMAGE.get(mime), bytes, mime, "public, max-age=31536000, immutable");
    }

    /** 사업자등록증. 상품 사진과 같은 버킷의 partners/ 아래 (주소를 아는 사람만 열 수 있는 무작위 이름) */
    public String putPartnerLicense(byte[] bytes, String mime) {
        return put("partners", FileTypes.LICENSE.get(mime), bytes, mime, null);
    }

    /** 상품 소개서 원본 — 무엇을 보고 등록 화면을 채웠는지 남겨둔다 */
    public String putProductDocument(byte[] bytes, String mime) {
        return put("product-docs", FileTypes.DOCUMENT.get(mime), bytes, mime, null);
    }

    private String put(String prefix, String ext, byte[] bytes, String mime, String cacheControl) {
        ensureBucket();
        String key = prefix + "/" + UUID.randomUUID() + "." + ext;
        PutObjectRequest.Builder req = PutObjectRequest.builder().bucket(bucket).key(key).contentType(mime);
        if (cacheControl != null) req.cacheControl(cacheControl);
        s3.putObject(req.build(), RequestBody.fromBytes(bytes));
        return publicUrl + "/" + key;
    }

    /** 버킷이 없으면 만들고 공개 읽기로 둔다 (로컬 RustFS 용. 배포 버킷은 미리 만들어 둔다) */
    private void ensureBucket() {
        if (bucketReady) return;
        synchronized (this) {
            if (bucketReady) return;
            try {
                s3.headBucket(b -> b.bucket(bucket));
            } catch (NoSuchBucketException e) {
                createPublicBucket();
            } catch (S3Exception e) {
                if (e.statusCode() != 404) throw e;
                createPublicBucket();
            }
            bucketReady = true;
        }
    }

    private void createPublicBucket() {
        log.info("저장소 버킷 {} 이 없어 공개 읽기로 만듭니다", bucket);
        s3.createBucket(b -> b.bucket(bucket));
        String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},"Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/*"]}]}"""
                .formatted(bucket);
        s3.putBucketPolicy(b -> b.bucket(bucket).policy(policy));
    }
}
