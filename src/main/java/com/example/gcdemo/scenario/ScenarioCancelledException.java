package com.example.gcdemo.scenario;

/**
 * 场景被用户取消时在检查点抛出；由 ScenarioRunner 捕获并转为 cancelled=true
 * 的部分结果，不向前端暴露为错误。
 */
public class ScenarioCancelledException extends RuntimeException {

    private final String phase;

    public ScenarioCancelledException(String phase) {
        super("scenario cancelled at phase: " + phase);
        this.phase = phase == null ? "" : phase;
    }

    public String phase() {
        return phase;
    }
}
