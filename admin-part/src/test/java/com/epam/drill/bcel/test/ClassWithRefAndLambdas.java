package com.epam.drill.bcel.test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ClassWithRefAndLambdas {

    public static Map<String, String> methodsMap = new HashMap<String, String>() {{
        put("firstMethod", "println");
        put("secondMethod", "lambda$secondMethod$0");
    }};

    void firstMethod(List<String> strings) {
        strings.stream().forEach(System.out::println);
    }

    void secondMethod(List<String> strings) {
        strings.stream().map(str -> str + "2").collect(Collectors.toList());
    }
}
