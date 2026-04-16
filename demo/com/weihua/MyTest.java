package com.weihua;

import java.io.File;

public class MyTest {
    public static void main(String[] args) {
        int count = 0;
        while (count < 30) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            File file = new File("/tmp/file.txt");
            boolean exists = file.exists();
            String path = file.getAbsolutePath();
            System.out.println("/tmp/file.txt exists: " + exists + ", path: " + path);
            count++;
        }
    }
}
