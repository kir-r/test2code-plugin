package com.epam.drill.bcel.test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ClassWithLambda {

    public static Map<String, String> methodsMap = new HashMap<String, String>() {{
        put("firstMethod", "lambda$firstMethod$0");
        put("secondMethod", "lambda$secondMethod$1");
    }};

    void firstMethod(List<String> strings) {
        strings.stream().forEach(str -> System.out.println(str));
    }

    void secondMethod(List<String> strings) {
        strings.stream().map(str -> str + "2").collect(Collectors.toList());
    }
}
