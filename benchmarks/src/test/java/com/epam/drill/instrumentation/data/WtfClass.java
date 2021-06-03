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


public class WtfClass implements Runnable {

   public static Integer qa = 500;
    boolean flag = false;
    int validCode = 10;

    public WtfClass() {
    }

    @Override
    public void run() {
            waytoHell(qa);
    }


    public void waytoHell(int i) {
        if (i == 10) {
            flag = true;
        } else if (i == 100000) {
            flag = false;
        } else {
            validCode = 1000;
        }
        waytoHell1(validCode);
    }


    public void waytoHell1(int i) {
        if (i == 120) {
            flag = true;
        } else if (i == 1003000) {
            flag = false;
        } else {
            validCode = 11000;
        }
        waytoHell2(validCode);
    }

    public void waytoHell2(int i) {
        if (i == 150) {
            flag = true;
        } else if (i == 10020001) {
            flag = false;
        } else {
            validCode = 12000;
        }
        waytoHell3(validCode);
    }

    public void waytoHell3(int i) {
        if (i == 1330) {
            flag = true;
        } else if (i == 1030000) {
            flag = false;
        } else {
            validCode = 10200;
        }
        assert validCode!=1000000200;
    }



}
