package com.epam.drill.bcel.test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ClassWithInnerLambda {

    public static Map<String, String> methodsMap = new HashMap<String, String>() {{
        put("method", "lambda$method$1");
        put("lambda$method$1", "lambda$null$0");
    }};

    void method(List<String> strings) {
        strings.stream().map(str ->
                Arrays.stream(str.split(".")).map(it -> it + "2").collect(Collectors.toList())
        ).collect(Collectors.toList());
    }
}
