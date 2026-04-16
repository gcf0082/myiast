package com.weihua;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;

public class MyTest {
    public static void main(String[] args) {
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            File file = new File("/tmp/file.txt");
            boolean exists = file.exists();
            String path = file.getAbsolutePath();
            System.out.println("/tmp/file.txt exists: " + exists + ", path: " + path);
            
            // 反射调用java.nio.file.Files.exists方法
            try {
                Class<?> filesClazz = Class.forName("java.nio.file.Files");
                Method existsMethod = filesClazz.getMethod("exists", Path.class, java.nio.file.LinkOption[].class);
                Path targetPath = Paths.get("/tmp/file.txt");
                boolean reflectResult = (boolean) existsMethod.invoke(null, targetPath, new java.nio.file.LinkOption[0]);
                System.out.println("反射调用Files.exists结果: " + reflectResult);
            } catch (Exception e) {
                System.err.println("反射调用异常: " + e);
                e.printStackTrace();
            }
        }
    }
}
