/**
 * Copyright 2020 EPAM Systems
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.epam.drill.instrumentation.data;

import java.util.*;

public class InvokeBigConditions implements Runnable {

    @Override
    public void run() {
        Random random = new Random(50);
        methodIfAndInFor(random.nextInt());
    }

    public int methodIfAndInFor(int s) {
        int sum = s;
        Random random = new Random(50);
        int firstRange = 3;
        int toMin = 10_000;
        int toMax = 100;
        for (int i = random.nextInt(firstRange); i <= random.nextInt(toMax) + toMin; i++) {
            sum += methodIfWithAnd(s);
            sum += methodDiffConditions(s);
            sum += methodIfAndAndOrOr(s);
            sum += methodSimpleIfAndAndOrOr(s);
            sum += methodWithCondition();
        }
        return sum;
    }

    int methodWithCondition() {
        int sum = 0;
        Random random = new Random(50);
        if (isaBoolean(random)) {
            sum += 110;
            if ((int) (Math.random() * 10000) % 2 == 0 | random.nextInt(100) + 1 > random.nextInt(200) | random.nextInt(100) + 2 > random.nextInt(200) | random.nextInt(100) + 3 > random.nextInt(200) | random.nextInt(100) + 4 > random.nextInt(200) | random.nextInt(100) + 5 > random.nextInt(200) | random.nextInt(100) + 6 > random.nextInt(200) | random.nextInt(100) + 7 > random.nextInt(200) | random.nextInt(100) + 8 > random.nextInt(200) | random.nextInt(100) + 9 > random.nextInt(200) | random.nextInt(100) + 10 > random.nextInt(200) | random.nextInt(100) + 11 > random.nextInt(200) | random.nextInt(100) + 12 > random.nextInt(200) | random.nextInt(100) + 13 > random.nextInt(200) | random.nextInt(100) + 14 > random.nextInt(200) | random.nextInt(100) + 15 > random.nextInt(200) | random.nextInt(100) + 16 > random.nextInt(200) | random.nextInt(100) + 17 > random.nextInt(200) | random.nextInt(100) + 18 > random.nextInt(200) | random.nextInt(100) >= random.nextInt(200)) {
                sum += 19;
                if ((int) (Math.random() * 10000) % 2 == 0 | random.nextInt(100) + 1 > random.nextInt(200) | random.nextInt(100) + 2 > random.nextInt(200) | random.nextInt(100) + 3 > random.nextInt(200) | random.nextInt(100) + 4 > random.nextInt(200) | random.nextInt(100) + 5 > random.nextInt(200) | random.nextInt(100) + 6 > random.nextInt(200) | random.nextInt(100) + 7 > random.nextInt(200) | random.nextInt(100) + 8 > random.nextInt(200) | random.nextInt(100) + 9 > random.nextInt(200) | random.nextInt(100) + 10 > random.nextInt(200) | random.nextInt(100) + 11 > random.nextInt(200) | random.nextInt(100) + 12 > random.nextInt(200) | random.nextInt(100) + 13 > random.nextInt(200) | random.nextInt(100) + 14 > random.nextInt(200) | random.nextInt(100) + 15 > random.nextInt(200) | random.nextInt(100) + 16 > random.nextInt(200) | random.nextInt(100) + 17 > random.nextInt(200) | random.nextInt(100) >= random.nextInt(200)) {
                    sum += 18;
                    if ((int) (Math.random() * 10000) % 2 == 0 | random.nextInt(100) + 1 > random.nextInt(200) | random.nextInt(100) + 2 > random.nextInt(200) | random.nextInt(100) + 3 > random.nextInt(200) | random.nextInt(100) + 4 > random.nextInt(200) | random.nextInt(100) + 5 > random.nextInt(200) | random.nextInt(100) + 6 > random.nextInt(200) | random.nextInt(100) + 7 > random.nextInt(200) | random.nextInt(100) + 8 > random.nextInt(200) | random.nextInt(100) + 9 > random.nextInt(200) | random.nextInt(100) + 10 > random.nextInt(200) | random.nextInt(100) + 11 > random.nextInt(200) | random.nextInt(100) + 12 > random.nextInt(200) | random.nextInt(100) + 13 > random.nextInt(200) | random.nextInt(100) + 14 > random.nextInt(200) | random.nextInt(100) + 15 > random.nextInt(200) | random.nextInt(100) + 16 > random.nextInt(200) | random.nextInt(100) >= random.nextInt(200)) {
                        sum += 17;
                        if ((int) (Math.random() * 10000) % 2 == 0 | random.nextInt(100) + 1 > random.nextInt(200) | random.nextInt(100) + 2 > random.nextInt(200) | random.nextInt(100) + 3 > random.nextInt(200) | random.nextInt(100) + 4 > random.nextInt(200) | random.nextInt(100) + 5 > random.nextInt(200) | random.nextInt(100) + 6 > random.nextInt(200) | random.nextInt(100) + 7 > random.nextInt(200) | random.nextInt(100) + 8 > random.nextInt(200) | random.nextInt(100) + 9 > random.nextInt(200) | random.nextInt(100) + 10 > random.nextInt(200) | random.nextInt(100) + 11 > random.nextInt(200) | random.nextInt(100) + 12 > random.nextInt(200) | random.nextInt(100) + 13 > random.nextInt(200) | random.nextInt(100) + 14 > random.nextInt(200) | random.nextInt(100) + 15 > random.nextInt(200) | random.nextInt(100) >= random.nextInt(200)) {
                            sum += 16;
                            if ((int) (Math.random() * 10000) % 2 == 0 | random.nextInt(100) + 1 > random.nextInt(200) | random.nextInt(100) + 2 > random.nextInt(200) | random.nextInt(100) + 3 > random.nextInt(200) | random.nextInt(100) + 4 > random.nextInt(200) | random.nextInt(100) + 5 > random.nextInt(200) | random.nextInt(100) + 6 > random.nextInt(200) | random.nextInt(100) + 7 > random.nextInt(200) | random.nextInt(100) + 8 > random.nextInt(200) | random.nextInt(100) + 9 > random.nextInt(200) | random.nextInt(100) + 10 > random.nextInt(200) | random.nextInt(100) + 11 > random.nextInt(200) | random.nextInt(100) + 12 > random.nextInt(200) | random.nextInt(100) + 13 > random.nextInt(200) | random.nextInt(100) + 14 > random.nextInt(200) | random.nextInt(100) >= random.nextInt(200)) {
                                sum += 15;
                                if ((int) (Math.random() * 10000) % 2 == 0 | random.nextInt(100) + 1 > random.nextInt(200) | random.nextInt(100) + 2 > random.nextInt(200) | random.nextInt(100) + 3 > random.nextInt(200) | random.nextInt(100) + 4 > random.nextInt(200) | random.nextInt(100) + 5 > random.nextInt(200) | random.nextInt(100) + 6 > random.nextInt(200) | random.nextInt(100) + 7 > random.nextInt(200) | random.nextInt(100) + 8 > random.nextInt(200) | random.nextInt(100) + 9 > random.nextInt(200) | random.nextInt(100) + 10 > random.nextInt(200) | random.nextInt(100) + 11 > random.nextInt(200) | random.nextInt(100) + 12 > random.nextInt(200) | random.nextInt(100) + 13 > random.nextInt(200) | random.nextInt(100) >= random.nextInt(200)) {
                                    sum += 14;
                                    if ((int) (Math.random() * 10000) % 2 == 0 | random.nextInt(100) + 1 > random.nextInt(200) | random.nextInt(100) + 2 > random.nextInt(200) | random.nextInt(100) + 3 > random.nextInt(200) | random.nextInt(100) + 4 > random.nextInt(200) | random.nextInt(100) + 5 > random.nextInt(200) | random.nextInt(100) + 6 > random.nextInt(200) | random.nextInt(100) + 7 > random.nextInt(200) | random.nextInt(100) + 8 > random.nextInt(200) | random.nextInt(100) + 9 > random.nextInt(200) | random.nextInt(100) + 10 > random.nextInt(200) | random.nextInt(100) + 11 > random.nextInt(200) | random.nextInt(100) + 12 > random.nextInt(200) | random.nextInt(100) >= random.nextInt(200)) {
                                        sum += 13;
                                        if ((int) (Math.random() * 10000) % 2 == 0 | random.nextInt(100) + 1 > random.nextInt(200) | random.nextInt(100) + 2 > random.nextInt(200) | random.nextInt(100) + 3 > random.nextInt(200) | random.nextInt(100) + 4 > random.nextInt(200) | random.nextInt(100) + 5 > random.nextInt(200) | random.nextInt(100) + 6 > random.nextInt(200) | random.nextInt(100) + 7 > random.nextInt(200) | random.nextInt(100) + 8 > random.nextInt(200) | random.nextInt(100) + 9 > random.nextInt(200) | random.nextInt(100) + 10 > random.nextInt(200) | random.nextInt(100) + 11 > random.nextInt(200) | random.nextInt(100) >= random.nextInt(200)) {
                                            sum += 12;
                                            if ((int) (Math.random() * 10000) % 2 == 0 | random.nextInt(100) + 1 > random.nextInt(200) | random.nextInt(100) + 2 > random.nextInt(200) | random.nextInt(100) + 3 > random.nextInt(200) | random.nextInt(100) + 4 > random.nextInt(200) | random.nextInt(100) + 5 > random.nextInt(200) | random.nextInt(100) + 6 > random.nextInt(200) | random.nextInt(100) + 7 > random.nextInt(200) | random.nextInt(100) + 8 > random.nextInt(200) | random.nextInt(100) + 9 > random.nextInt(200) | random.nextInt(100) + 10 > random.nextInt(200) | random.nextInt(100) >= random.nextInt(200)) {
                                                sum += 11;

                                            } else {
                                                sum -= 11;

                                            }
                                        } else {
                                            sum -= 12;

                                        }
                                    } else {
                                        sum -= 13;

                                    }
                                } else {
                                    sum -= 14;

                                }
                            } else {
                                sum -= 15;

                            }
                        } else {
                            sum -= 16;

                        }
                    } else {
                        sum -= 17;

                    }
                } else {
                    sum -= 18;

                }
            } else {
                sum -= 19;

            }
        } else {
            sum -= 110;

        }
        return sum;
    }

    private boolean isaBoolean(Random random) {
        return (int) (Math.random() * 10000) % 2 == 0 | random.nextInt(100) + 1 > random.nextInt(200) | random.nextInt(100) + 2 > random.nextInt(200) | random.nextInt(100) + 3 > random.nextInt(200) | random.nextInt(100) + 4 > random.nextInt(200) | random.nextInt(100) + 5 > random.nextInt(200) | random.nextInt(100) + 6 > random.nextInt(200) | random.nextInt(100) + 7 > random.nextInt(200) | random.nextInt(100) + 8 > random.nextInt(200) | random.nextInt(100) + 9 > random.nextInt(200) | random.nextInt(100) + 10 > random.nextInt(200) | random.nextInt(100) + 11 > random.nextInt(200) | random.nextInt(100) + 12 > random.nextInt(200) | random.nextInt(100) + 13 > random.nextInt(200) | random.nextInt(100) + 14 > random.nextInt(200) | random.nextInt(100) + 15 > random.nextInt(200) | random.nextInt(100) + 16 > random.nextInt(200) | random.nextInt(100) + 17 > random.nextInt(200) | random.nextInt(100) + 18 > random.nextInt(200) | random.nextInt(100) + 19 > random.nextInt(200) | random.nextInt(100) >= random.nextInt(200);
    }

    int methodIfWithAnd(int s) {
        int sum = s;
        Random random = new Random(50);
        if (isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random) & isaBoolean(random)) {
            sum += 1000;
        } else {
            sum -= 1000;
        }
        return sum;
    }

    int methodIfAndAndOrOr(int s) {
        int sum = s;
        Random random = new Random(50);
        if (((int) (Math.random() * 10000) % 2 == 0 || random.nextInt(100) + 1 > random.nextInt(200) || random.nextInt(100) + 2 > random.nextInt(200))
                && ((int) (Math.random() * 10000) % 2 == 0 || random.nextInt(100) + 1 > random.nextInt(200) || random.nextInt(100) + 2 > random.nextInt(200))
                && ((int) (Math.random() * 10000) % 2 == 0 || random.nextInt(100) + 1 > random.nextInt(200) || random.nextInt(100) + 2 > random.nextInt(200))
        ) {
            sum += 1000;
        } else {
            sum -= 1000;
        }
        return sum;
    }

    int methodSimpleIfAndAndOrOr(int s) {
        int sum = s;
        if (((int) (Math.random() * 10000) % 2 == 0 || s > 10)
                && ((int) (Math.random() * 10000) % 2 == 0 || s <= 10)
        ) {
            sum += 1000;
        } else {
            sum -= 1000;
        }
        return sum;
    }

    int methodDiffConditions(int s) {
        int sum = s;

        if ((int) (Math.random() * 10000) % 2 == 0) {
            sum += 1000;
        } else {
            sum -= 1000;
        }

        if ((int) (Math.random() * 10000) % 2 == 0 || s > 10) {
            sum += 1000;
        } else {
            sum -= 1000;
        }

        if ((int) (Math.random() * 10000) % 2 == 0 && s > 10) {
            sum += 1000;
        } else {
            sum -= 1000;
        }

        if ((int) (Math.random() * 10000) % 2 == 0 | s > 10) {
            sum += 1000;
        } else {
            sum -= 1000;
        }

        if ((int) (Math.random() * 10000) % 2 == 0 & s > 10) {
            sum += 1000;
        } else {
            sum -= 1000;
        }

        return sum;
    }
}
