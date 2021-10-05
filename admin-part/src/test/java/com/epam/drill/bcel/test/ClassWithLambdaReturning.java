package com.epam.drill.bcel.test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ClassWithLambdaReturning {
    public static Map<String, String> methodsMap = new HashMap<String, String>() {{
        put("method", "lambda$method$1");
        put("lambda$method$1", "lambda$null$0");
    }};

    Function<String, Set<Integer>> method() {
        return (value) -> {
            String[] split = value.split(":");
            Set<Integer> sum = Arrays.stream(split).map(str -> {
                        Integer integer = Integer.getInteger(str);
                        return integer + 10;
                    }
            ).collect(Collectors.toSet());
            return sum;
        };
    }

    void firstMethod() {
        method().apply("5:8");
    }
}
