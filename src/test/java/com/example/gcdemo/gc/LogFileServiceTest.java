package com.example.gcdemo.gc;

import com.example.gcdemo.config.DemoProperties;
import com.example.gcdemo.error.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 日志 tail 的行数、截断与路径穿越安全测试。
 */
class LogFileServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void tailsLastLinesAndListsGcLogFirst() throws Exception {
        Files.writeString(tempDir.resolve("safepoint-1.log"), "a\nb\n");
        Files.writeString(tempDir.resolve("gc-1.log"), "one\ntwo\nthree\nfour\n");
        DemoProperties properties = new DemoProperties();
        properties.setLogDir(tempDir.toString());
        LogFileService service = new LogFileService(properties);

        List<LogFileInfo> files = service.list();
        LogTail tail = service.tail("gc-1.log", 2);

        assertThat(files).isNotEmpty();
        assertThat(files.getFirst().name()).startsWith("gc-");
        assertThat(tail.content()).isEqualTo("three\nfour\n");
        assertThat(tail.truncated()).isFalse();
    }

    @Test
    void rejectsTraversalAndNonLogFiles() {
        DemoProperties properties = new DemoProperties();
        properties.setLogDir(tempDir.toString());
        LogFileService service = new LogFileService(properties);

        assertThatThrownBy(() -> service.tail("../pom.xml", 10))
                .isInstanceOf(ApiException.class)
                .extracting("status.value")
                .isEqualTo(400);
        assertThatThrownBy(() -> service.tail("/etc/passwd", 10))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.tail("missing.log", 10))
                .isInstanceOf(ApiException.class)
                .extracting("status.value")
                .isEqualTo(404);
        assertThatThrownBy(() -> service.tail("gc-1.txt", 10))
                .isInstanceOf(ApiException.class)
                .extracting("status.value")
                .isEqualTo(400);
    }
}
