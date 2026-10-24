package com.example.gcdemo.scenario;

import java.util.List;

/**
 * 面向 UI 和文档的场景描述。
 *
 * @param risk           低/中/高，提示是否会触发 Full GC 或大量分配
 * @param triggersFullGc 是否可能显式触发 STW Full GC
 * @param params         可调参数元数据
 * @param expectedLog    帮助学习者知道应该去日志里找什么
 */
public record ScenarioInfo(
        String id,
        String title,
        String goal,
        String risk,
        boolean triggersFullGc,
        List<ParamSpec> params,
        List<String> expectedLog
) {
}
