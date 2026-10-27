package com.crosshub.backoffice.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.crosshub.backoffice.product.ProductImages;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StoredFilesTest {

    static final String BASE = "https://cdn.example.com/product-images";

    @BeforeEach
    void setUp() { StoredFiles.configure(BASE); }

    @AfterEach
    void tearDown() { StoredFiles.configure(null); }

    @Test
    void 우리_저장소_URL만_key로_바꾼다() {
        assertThat(StoredFiles.key(BASE + "/products/ab-12.png", "products")).isEqualTo("products/ab-12.png");
        assertThat(StoredFiles.key("products/ab-12.png", "products")).isEqualTo("products/ab-12.png");
        assertThat(StoredFiles.key("/seed-images/onsen.jpg", "products")).isEqualTo("/seed-images/onsen.jpg");
        assertThat(StoredFiles.key("https://evil.example.com/products/a.png", "products")).isNull();
        assertThat(StoredFiles.key(BASE + "/partners/a.pdf", "products")).isNull();
        assertThat(StoredFiles.key(BASE + "/products/../partners/a.pdf", "products")).isNull();
        assertThat(StoredFiles.key(BASE + "/products/x/y.png", "products")).isNull();
    }

    @Test
    void key를_URL로_돌려준다() {
        assertThat(StoredFiles.url("products/a.png")).isEqualTo(BASE + "/products/a.png");
        assertThat(StoredFiles.url("/seed-images/a.jpg")).isEqualTo("/seed-images/a.jpg");
        assertThat(StoredFiles.url("http://old.example.com/a.png")).isEqualTo("http://old.example.com/a.png");
        assertThat(StoredFiles.url(null)).isNull();
    }

    @Test
    void 상품_입력의_세_자리_사진을_모두_바꾼다() throws Exception {
        ObjectMapper om = new ObjectMapper();
        ObjectNode input = (ObjectNode) om.readTree("""
                {"images":["%1$s/products/a.png","https://evil.example.com/x.png","%1$s/products/a.png"],
                 "blocks":[{"title":"t","images":["%1$s/products/b.png"]}],
                 "priceTable":{"priceImages":["%1$s/products/c.png"]}}""".formatted(BASE));
        ProductImages.toKeys(input);
        assertThat(input.toString()).isEqualTo(
                "{\"images\":[\"products/a.png\"],\"blocks\":[{\"title\":\"t\",\"images\":[\"products/b.png\"]}],\"priceTable\":{\"priceImages\":[\"products/c.png\"]}}");
        assertThat(ProductImages.toUrls(input).path("blocks").path(0).path("images").path(0).asText()).isEqualTo(BASE + "/products/b.png");
        assertThat(ProductImages.keys(List.of(BASE + "/products/a.png", "nope"))).containsExactly("products/a.png");
        assertThat(ProductImages.priceTableWithUrls("{\"priceImages\":[\"products/c.png\"]}")).contains(BASE + "/products/c.png");
    }
}
