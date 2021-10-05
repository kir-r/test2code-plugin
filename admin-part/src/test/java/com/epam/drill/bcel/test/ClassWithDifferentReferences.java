package com.epam.drill.bcel.test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ClassWithDifferentReferences {

    public static Map<String, String> methodsMap = new HashMap<String, String>() {{
        put("firstMethod", "println");
        put("secondMethod", "lambda$secondMethod$0");
        put("thirdMethod", "println");
        put("fourthMethod", "lambda$fourthMethod$1");
    }};

    void firstMethod(List<String> strings) {
        strings.stream().forEach(System.out::println);
    }

    void secondMethod(List<String> strings) {
        strings.stream().map(str ->
                str + "2"
        ).collect(Collectors.joining());
    }

    void thirdMethod(List<Integer> strings) {
        strings.stream().forEach(System.out::println);
    }

    void fourthMethod(List<String> strings) {
        strings.stream().map(str ->
                str + "4"
        ).collect(Collectors.joining());
    }
}
