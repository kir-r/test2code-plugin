package com.epam.drill.bcel.test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ClassWithInnerLambdas {
    public static Map<String, String> methodsMap = new HashMap<String, String>() {{
        put("firstMethod", "lambda$firstMethod$1");
        put("lambda$firstMethod$1", "lambda$null$0");
        put("secondMethod", "lambda$secondMethod$3");
        put("lambda$secondMethod$3", "lambda$null$2");
    }};

    void firstMethod(List<String> strings) {
        strings.stream().map(str ->
                Arrays.stream(str.split(".")).map(it -> it + "2").collect(Collectors.toList())
        ).collect(Collectors.toList());
    }


    void secondMethod(List<String> strings) {
        strings.stream().map(str ->
                Arrays.stream(str.split(".")).map(it -> it + "4").collect(Collectors.toList())
        ).collect(Collectors.toList());
    }

}
