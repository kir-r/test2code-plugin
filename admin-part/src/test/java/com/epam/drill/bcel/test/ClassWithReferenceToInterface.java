package com.epam.drill.bcel.test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ClassWithReferenceToInterface {
    public static Map<String, String> methodsMap = new HashMap<String, String>() {{
        put("firstMethod", "compareTo");
        put("secondMethod", "lambda$secondMethod$0");
        put("thirdMethod", "lambda$thirdMethod$1");
    }};

    void firstMethod(List<String> strings) {
        strings.stream().sorted(Comparable::compareTo).collect(Collectors.joining());
    }

    void secondMethod(List<String> strings) {
        strings.stream().map(str -> str + "2").collect(Collectors.toList());
    }

    void thirdMethod(List<String> strings) {
        strings.stream().map(str -> str + "3").collect(Collectors.toList());
    }
}
