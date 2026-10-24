package com.example.gcdemo.scenario.impl;

import com.example.gcdemo.error.ApiException;
import com.example.gcdemo.scenario.GcScenario;
import com.example.gcdemo.scenario.ParamSpec;
import com.example.gcdemo.scenario.ScenarioContext;
import com.example.gcdemo.scenario.ScenarioInfo;
import com.example.gcdemo.scenario.ScenarioOutcome;
import com.example.gcdemo.scenario.ScenarioParams;
import com.example.gcdemo.scenario.ScenarioSupport;
import com.example.gcdemo.util.ByteSize;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 类卸载场景：动态编译并加载 500 个嵌套类，丢弃独立 ClassLoader 后触发 Full GC。
 *
 * <p>核心不是“Metaspace 数字下降”，而是 {@code class,unload} 日志出现
 * {@code unloading class DynamicClasses$Cxxx}。Metaspace committed 可能不会立即
 * 归还操作系统，因此只看 RSS 或 committed 会误判。</p>
 */
@Component
public class ClassUnloadScenario implements GcScenario {

    private final ScenarioSupport support;

    public ClassUnloadScenario(ScenarioSupport support) {
        this.support = support;
    }

    @Override
    public String id() {
        return "class-unload";
    }

    @Override
    public int order() {
        return 90;
    }

    @Override
    public ScenarioInfo info() {
        return new ScenarioInfo(
                id(),
                "Class Unload：类卸载与 Metaspace",
                "动态编译并加载一批类，丢弃 ClassLoader 后触发 Full GC，观察 Metaspace 回收和 class unload 日志。",
                "高（显式 Full GC）",
                true,
                List.of(new ParamSpec("count", "动态类数量", 500, 50, 500, "个")),
                List.of("class,unload", "Metaspace", "Pause Full")
        );
    }

    /**
     * 动态编译/加载类，丢弃 ClassLoader 后触发 Full GC，观察类卸载。
     *
     * <p>ClassLoader、Class 对象和实例都必须不可达；即使 Metaspace committed
     * 不回落，只要出现 {@code unloading class} 就说明卸载路径已执行。</p>
     */
    @Override
    public ScenarioOutcome run(ScenarioParams params, ScenarioContext ctx) {
        support.checkFullGcAllowed();
        int count = params.get("count", 500);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new ApiException(HttpStatus.NOT_IMPLEMENTED,
                    "当前 JVM 没有 javac，请使用完整 JDK 启动",
                    "JRE 不包含 javax.tools.JavaCompiler。");
        }

        Path dir = null;
        long before = support.heap().metaspaceUsed();
        long unloadedBefore = support.sideLogs().unloadedTotal();
        try {
            dir = Files.createTempDirectory("g1-class-unload-");
            Path source = dir.resolve("DynamicClasses.java");
            Files.writeString(source, generateSource(count), StandardCharsets.UTF_8);
            ctx.phase("动态编译 " + count + " 个嵌套类", 30);
            boolean compiled = compile(compiler, source, dir);
            if (!compiled) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "动态类编译失败");
            }

            ctx.phase("加载动态类", 55);
            List<Class<?>> classes = new ArrayList<>(count);
            long loaded;
            // try-with-resources：即使 Class.forName 抛出也保证 loader 关闭，不留悬挂资源。
            try (URLClassLoader loader = new URLClassLoader(new URL[]{dir.toUri().toURL()},
                    getClass().getClassLoader())) {
                for (int i = 0; i < count; i++) {
                    if (i % 100 == 0) {
                        ctx.checkCancelled();
                    }
                    classes.add(Class.forName("DynamicClasses$C" + i, true, loader));
                }
                loaded = support.heap().metaspaceUsed();
                classes.clear();
            }

            ctx.phase("丢弃 ClassLoader 并触发 Full GC", 80);
            System.gc();
            support.sleep(ctx, 1200);
            long after = support.heap().metaspaceUsed();
            if (after >= loaded - 64 * 1024) {
                System.gc();
                support.sleep(ctx, 800);
                after = support.heap().metaspaceUsed();
            }
            ctx.phase("汇总", 95);
            // 主动消费侧日志，拿到 unloading class 行的一手计数
            support.sideLogs().poll();
            long unloadedDelta = support.sideLogs().unloadedTotal() - unloadedBefore;

            return new ScenarioOutcome(
                    0,
                    support.retained().usedBytes() / ByteSize.MB,
                    List.of(
                            "动态加载 " + count + " 个嵌套类",
                            "Metaspace " + ByteSize.human(before) + " -> " + ByteSize.human(loaded)
                                    + " -> " + ByteSize.human(after),
                            "unloading class 行数增量 " + unloadedDelta
                                    + (unloadedDelta > 0 ? "（一手证据）" : "（未观测到，检查 class-unload 日志）")
                    ),
                    unloadedDelta > 0
                            ? "已观测到 " + unloadedDelta + " 个类卸载；对照 class-unload 日志的 unloading 行与 Metaspace 回落。"
                            : "class unload 依赖 ClassLoader 不可达；查看 class-unload 日志中的 unloading 行，并与 Metaspace 回落对照。",
                    Map.of(
                            "count", count,
                            "unloadedClasses", unloadedDelta,
                            "metaspaceBeforeMb", before / ByteSize.MB,
                            "metaspaceLoadedMb", loaded / ByteSize.MB,
                            "metaspaceAfterMb", after / ByteSize.MB
                    )
            );
        } catch (IOException | ClassNotFoundException ex) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "类卸载实验失败: " + ex.getMessage());
        } finally {
            deleteRecursively(dir);
        }
    }

    private boolean compile(JavaCompiler compiler, Path source, Path output) throws IOException {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(
                diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = manager.getJavaFileObjects(source.toFile());
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, manager, diagnostics, List.of("-proc:none", "-d", output.toString()),
                    null, units);
            return Boolean.TRUE.equals(task.call());
        }
    }

    private String generateSource(int count) {
        StringBuilder source = new StringBuilder("public class DynamicClasses {\n");
        for (int i = 0; i < count; i++) {
            source.append("  public static class C").append(i).append(" {\n")
                    .append("    public static final int VALUE = ").append(i).append(";\n")
                    .append("    public int a() { return VALUE; }\n")
                    .append("    public int b() { return VALUE + 1; }\n")
                    .append("    public int c() { return VALUE + 2; }\n")
                    .append("  }\n");
        }
        source.append("}\n");
        return source.toString();
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Temporary files are best-effort cleanup.
                }
            });
        } catch (IOException ignored) {
            // Ignore cleanup failure.
        }
    }
}
