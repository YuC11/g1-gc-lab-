package com.example.gcdemo.scenario;

/**
 * GC 教学场景的统一契约。
 *
 * <p>每个实现都是一个独立可执行实验：声明 UI 参数、执行分配/等待逻辑、返回观察结果。
 * 场景由 {@link ScenarioRunner} 串行执行；不允许实现自己创建不受控的后台线程。</p>
 */
public interface GcScenario {

    /**
     * 稳定的 URL id，例如 {@code mixed-gc}。
     */
    String id();

    /**
     * UI 卡片和注册表的排序权重，越小越靠前。
     */
    default int order() {
        return 100;
    }

    /**
     * 场景元数据：标题、目的、风险、参数和预期日志。参数名即请求体键名。
     */
    ScenarioInfo info();

    /**
     * 执行一次实验。实现必须是同步阻塞的；Runner 已保证串行。
     *
     * <p>参数已由 Runner 按 ParamSpec 的 min/max 严格校验，实现只需为空值套默认值，
     * 不再自行 clamp。长循环应周期性 {@code ctx.checkCancelled()} 并通过
     * {@code ctx.phase(...)} 上报进度；所有等待应使用 ScenarioSupport 的 ctx 版本，
     * 它们会把时长夹到墙钟预算内。</p>
     *
     * @param params 已校验的按场景命名参数
     * @param ctx    进度/取消/预算上下文
     */
    ScenarioOutcome run(ScenarioParams params, ScenarioContext ctx);
}
