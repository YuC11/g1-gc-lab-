package com.example.gcdemo.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web 层回归测试：状态、场景目录、小规模场景、禁用 Full GC 与日志路径穿越。
 */
@SpringBootTest(properties = {
        "demo.allow-full-gc=false",
        "demo.log-dir=target/test-logs",
        "demo.max-allocation-mb=256"
})
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void statusExposesG1AndHeapInformation() throws Exception {
        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jvm.javaVersion").value(org.hamcrest.Matchers.startsWith("21")))
                .andExpect(jsonPath("$.jvm.selectedFlags.UseG1GC").value("true"))
                .andExpect(jsonPath("$.jvm.regionSizeBytes").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.heap.heapMax").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void scenarioCatalogContainsAllScenarios() throws Exception {
        mockMvc.perform(get("/api/scenarios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == 'young-gc')]").exists())
                .andExpect(jsonPath("$[?(@.id == 'mixed-gc')]").exists())
                .andExpect(jsonPath("$[?(@.id == 'class-unload')]").exists())
                .andExpect(jsonPath("$[?(@.id == 'reset')]").exists());
    }

    @Test
    void smallYoungScenarioRunsAndReturnsMetrics() throws Exception {
        mockMvc.perform(post("/api/scenarios/young-gc/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"totalMb\":32,\"chunkKb\":128}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario").value("young-gc"))
                .andExpect(jsonPath("$.allocatedMb").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.observations").isArray())
                .andExpect(jsonPath("$.cancelled").value(false));
    }

    @Test
    void rejectsUnknownAndOutOfRangeParams() throws Exception {
        // 未知参数：400，hint 列出可用参数
        mockMvc.perform(post("/api/scenarios/young-gc/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bogusParam\":10}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("bogusParam")))
                .andExpect(jsonPath("$.hint").value(org.hamcrest.Matchers.containsString("totalMb")));

        // 越界参数：400，报允许范围
        mockMvc.perform(post("/api/scenarios/young-gc/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"totalMb\":999999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("越界")));
    }

    @Test
    void historyAndCancelEndpointsWork() throws Exception {
        // 尚无运行时取消应返回 accepted=false
        mockMvc.perform(post("/api/scenarios/current/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(false));

        mockMvc.perform(get("/api/scenarios/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // 运行一次小场景后，历史应包含该场景（可能还有同测试类的其他运行，用 filter 断言）
        mockMvc.perform(post("/api/scenarios/young-gc/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"totalMb\":32}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/scenarios/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.scenario == 'young-gc')]").exists());
    }

    @Test
    void resetWithoutExplicitGcWorks() throws Exception {
        mockMvc.perform(post("/api/scenarios/reset").param("gc", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenario").value("reset"))
                .andExpect(jsonPath("$.cancelled").value(false));
    }

    @Test
    void statusExposesScenarioProgressField() throws Exception {
        // 空闲时进度为 null（字段存在，值为 null）
        mockMvc.perform(get("/api/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scenarioProgress").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void disabledFullGcAndUnknownScenarioAreRejected() throws Exception {
        mockMvc.perform(post("/api/scenarios/full-gc/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/scenarios/does-not-exist/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void logTraversalIsRejected() throws Exception {
        mockMvc.perform(get("/api/logs/tail").param("name", "../pom.xml"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("日志名称不合法"));
    }
}
