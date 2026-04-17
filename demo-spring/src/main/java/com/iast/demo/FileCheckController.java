package com.iast.demo;

import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api")
public class FileCheckController {

    @GetMapping("/hello")
    public String hello() {
        return "Hello from Spring Boot IAST Demo!";
    }

    @PostMapping("/check-file")
    public String checkFile(@RequestParam String path) {
        File file = new File(path);
        boolean exists = file.exists();
        return "File exists: " + exists;
    }

    @GetMapping("/check-file-get")
    public String checkFileGet(@RequestParam String path) {
        File file = new File(path);
        boolean exists = file.exists();
        return "File exists: " + exists;
    }

    @GetMapping("/list-dir")
    public String listDir(@RequestParam String path) throws IOException {
        try (Stream<java.nio.file.Path> s = Files.list(Paths.get(path))) {
            return "entries: " + s.count();
        }
    }

    @GetMapping("/echo")
    public String echo(@RequestParam String msg,
                       @RequestParam(defaultValue = "1") int times) {
        return repeatMsg(msg, times);
    }

    private String repeatMsg(String msg, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) sb.append(msg);
        return sb.toString();
    }

    /**
     * 反射调用 java.nio.file.Files.exists —— 验证 Agent 能拦截通过反射发起的调用
     */
    @GetMapping("/reflect-exists")
    public String reflectExists(@RequestParam String path) throws Exception {
        Class<?> filesClazz = Class.forName("java.nio.file.Files");
        Method existsMethod = filesClazz.getMethod("exists", Path.class, LinkOption[].class);
        Path target = Paths.get(path);
        boolean exists = (boolean) existsMethod.invoke(null, target, new LinkOption[0]);
        return "reflect Files.exists(" + path + ") = " + exists;
    }
}