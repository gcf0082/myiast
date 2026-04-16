package com.iast.demo;

import org.springframework.web.bind.annotation.*;
import java.io.File;

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
}