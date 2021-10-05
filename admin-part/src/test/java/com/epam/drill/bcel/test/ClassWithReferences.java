package com.epam.drill.bcel.test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class ClassWithReferences {

    public static Map<String, String> methodsMap = new HashMap<String, String>() {{
        put("method", "lambda$method$0");
    }};

    void method(List<String> strings) {
        Integer valuer = new Random().nextInt();
        strings.stream().forEach(System.out::printf);
    }
}
