package com.yuzhihuihe.apiplatform.platform.db;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.yuzhihuihe.apiplatform.platform.TestJwtKeyRing;

/**
 * 只读诊断：把整条迁移链跑完后**真实的**套餐目录状态打出来。
 *
 * <p>存在的理由：{@code PackageCatalogTest} 里有几条断言依赖"迁移链结束后每个计划恰好有一个
 * 在售版本、版本号是多少"。当一条后续迁移（这里是 V104 的涨价换版）把版本号往前推一格，
 * 那些断言就会以一个看起来像"业务坏了"的样子失败（"找不到已发布版本"、"版本号从 3 变成 4"），
 * 而真正发生的事只是**测试对迁移后的目录形状的假设过期了**。
 *
 * <p>这个用例不做断言 —— 它只打印状态，好让"到底是谁把版本号推了一格"这个问题有一份可复核的
 * 原始数据，而不是靠猜。生产部署前用它核对：迁移链跑完后的目录形状，是否与生产库一致。
 */
@Testcontainers
@SpringBootTest(properties = {
        "spring.flyway.default-schema=platform",
        "spring.flyway.schemas=platform",
        "spring.jpa.hibernate.ddl-auto=none",
        "spring.data.redis.repositories.enabled=false"
})
class CatalogStateDiagnosticTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        TestJwtKeyRing.configure(registry);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void dumpCatalogShapeAfterTheWholeMigrationChain() {
        System.out.println("### DIAG flyway head: "
                + jdbc.queryForObject("SELECT max(version::numeric) FROM platform.flyway_schema_history",
                        Integer.class));

        System.out.println("### DIAG in-sale version per plan (DISTINCT ON is what the storefront uses):");
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT DISTINCT ON (p.code) p.code, v.version, v.status, v.price_amount,
                       v.usage_dimension, v.duration_days
                FROM platform.package_plan p
                JOIN platform.package_plan_version v ON v.package_plan_id = p.id
                WHERE v.status = 'PUBLISHED'
                ORDER BY p.code, v.version DESC
                """)) {
            System.out.println("### DIAG   " + row);
        }

        System.out.println("### DIAG every version row (code, version, status, price):");
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT p.code, v.version, v.status, v.price_amount, v.usage_dimension
                FROM platform.package_plan p
                JOIN platform.package_plan_version v ON v.package_plan_id = p.id
                ORDER BY p.code, v.version
                """)) {
            System.out.println("### DIAG   " + row);
        }

        System.out.println("### DIAG plans with more than one PUBLISHED version:");
        List<Map<String, Object>> dupes = jdbc.queryForList("""
                SELECT p.code, count(*) AS published_count,
                       string_agg(v.version::text, ',' ORDER BY v.version) AS versions
                FROM platform.package_plan p
                JOIN platform.package_plan_version v ON v.package_plan_id = p.id
                WHERE v.status = 'PUBLISHED'
                GROUP BY p.code
                HAVING count(*) > 1
                """);
        if (dupes.isEmpty()) {
            System.out.println("### DIAG   none");
        }
        for (Map<String, Object> row : dupes) {
            System.out.println("### DIAG   " + row);
        }

        System.out.println("### DIAG VIDEO_STARTER rows (the plan the failing fixture targets):");
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT v.version, v.status, v.price_amount, v.usage_dimension
                FROM platform.package_plan p
                JOIN platform.package_plan_version v ON v.package_plan_id = p.id
                WHERE p.code = 'VIDEO_STARTER'
                ORDER BY v.version
                """)) {
            System.out.println("### DIAG   " + row);
        }

        System.out.println("### DIAG image dimension rows (V106 territory):");
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT model_code, usage_dimension, resolution_tier, effective_unit_cost,
                       retail_unit_price, price_version
                FROM platform.model_usage_dimension
                ORDER BY model_code
                """)) {
            System.out.println("### DIAG   " + row);
        }

        System.out.println("### DIAG model_usage_dimension tier constraint:");
        System.out.println("### DIAG   " + jdbc.queryForObject("""
                SELECT pg_get_constraintdef(c.oid)
                FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = 'platform' AND t.relname = 'model_usage_dimension'
                  AND c.conname = 'ck_model_usage_dimension_tier'
                """, String.class));

        // ------------------------------------------------------------------
        // The stale-fixture survey. These are the fixed UUIDs the test sources
        // hardcode; printing their resolved plan/version/status once is far
        // cheaper than learning each one from a failed assertion.
        // ------------------------------------------------------------------
        System.out.println("### DIAG fixed fixture UUIDs resolved against the migrated schema:");
        for (String id : new String[] {
                "00000000-0000-4000-8000-000000000011",
                "00000000-0000-4000-8000-000000000014",
                "00000000-0000-4000-8000-000000000101",
                "00000000-0000-4000-8000-000000000102",
                "00000000-0000-4000-8000-000000000103",
                "00000000-0000-4000-8000-000000000104",
                "00000000-0000-4000-8000-000000000105",
                "00000000-0000-4000-8000-000000000106",
                "00000000-0000-4000-8000-000000000111",
                "00000000-0000-4000-8000-000000000112",
                "00000000-0000-4000-8000-000000000122",
                "00000000-0000-4000-8000-000000000123",
                "00000000-0000-4000-8000-000000000132",
                "00000000-0000-4000-8000-000000000133",
                "00000000-0000-4000-8000-000000000204",
                "00000000-0000-4000-8000-000000000214",
                "00000000-0000-4000-8000-000000000224",
                "00000000-0000-4000-8000-000000000234",
                "00000000-0000-4000-8000-000000000321",
        }) {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT p.code, v.version, v.status, v.price_amount, v.usage_dimension
                    FROM platform.package_plan_version v
                    JOIN platform.package_plan p ON p.id = v.package_plan_id
                    WHERE v.id = ?::uuid
                    """, id);
            System.out.println("### DIAG   " + id + " -> "
                    + (rows.isEmpty() ? "NO SUCH ROW" : rows.get(0).toString()));
        }

        System.out.println("### DIAG the PUBLISHED version id of every plan "
                + "(the values stale fixtures should point at):");
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT p.code, v.id AS version_id, v.version, v.usage_dimension
                FROM platform.package_plan p
                JOIN platform.package_plan_version v ON v.package_plan_id = p.id
                WHERE v.status = 'PUBLISHED'
                ORDER BY p.code
                """)) {
            System.out.println("### DIAG   " + row);
        }
    }
}
