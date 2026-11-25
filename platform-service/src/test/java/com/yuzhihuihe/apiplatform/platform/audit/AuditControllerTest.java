package com.yuzhihuihe.apiplatform.platform.audit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuditControllerTest {

    @Test
    void exposesPagedAuditFilters() throws Exception {
        AuditQueryService service = mock(AuditQueryService.class);
        when(service.query(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AuditQueryService.PageResult<>(List.of(), 0, 0, 20));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AuditController(service)).build();
        UUID actorId = UUID.fromString("71000000-0000-4000-8000-000000000001");
        Instant from = Instant.parse("2026-07-30T00:00:00Z");
        Instant to = Instant.parse("2026-07-31T00:00:00Z");

        mvc.perform(get("/console/platform/admin/audit")
                        .param("actorUserId", actorId.toString())
                        .param("action", "PACKAGE_GRANT_CREATED")
                        .param("resourceType", "GRANT")
                        .param("resourceId", "grant-1")
                        .param("outcome", "SUCCESS")
                        .param("from", from.toString())
                        .param("to", to.toString())
                        .param("sort", "action")
                        .param("direction", "asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));

        verify(service).query(eq(new AuditQueryService.PageRequest(0, 20)), eq(actorId),
                eq("PACKAGE_GRANT_CREATED"), eq("GRANT"), eq("grant-1"), eq("SUCCESS"),
                eq(from), eq(to), eq("action"), eq("asc"));
    }
}
