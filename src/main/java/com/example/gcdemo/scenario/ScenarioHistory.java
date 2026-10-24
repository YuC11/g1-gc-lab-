package com.example.gcdemo.scenario;

import com.example.gcdemo.config.DemoProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * 最近场景运行结果的环形缓冲，支撑 UI 的“运行历史 + A/B 对比”。
 *
 * <p>单变量 A/B 实验需要把两次运行的关键指标并排比较；旧实现只有
 * {@code lastScenario} 一个槽位，对比只能靠手抄。容量由
 * {@code demo.history-size} 控制，默认 20。</p>
 */
@Component
public class ScenarioHistory {

    private final int capacity;
    private final Deque<ScenarioResult> results = new ArrayDeque<>();

    public ScenarioHistory(DemoProperties properties) {
        this.capacity = Math.max(1, properties.getHistorySize());
    }

    public synchronized void add(ScenarioResult result) {
        if (results.size() >= capacity) {
            results.removeFirst();
        }
        results.addLast(result);
    }

    /**
     * @return 最近运行，最新在前
     */
    public synchronized List<ScenarioResult> recent() {
        List<ScenarioResult> list = new ArrayList<>(results);
        Collections.reverse(list);
        return list;
    }

    public synchronized int size() {
        return results.size();
    }
}
