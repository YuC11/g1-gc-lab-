package com.example.gcdemo.scenario;

/**
 * 场景运行进度快照，出现在 /api/status 的 scenarioProgress 字段（空闲时为 null）。
 *
 * @param phase        当前阶段文本，例如“阶段 2/3：制造存活批次”
 * @param percent      0..100；未知进度时为 -1
 * @param cancelled    已请求取消（尚未生效到检查点时也会短暂为 true）
 * @param remainingMs  墙钟预算剩余毫秒
 */
public record ScenarioProgress(String phase, int percent, boolean cancelled, long remainingMs) {
}
